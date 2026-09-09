package com.commerceflow.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.dto.AddressRequest;
import com.commerceflow.authservice.dto.AddressResponse;
import com.commerceflow.authservice.entity.AddressType;
import com.commerceflow.authservice.entity.UserAddress;
import com.commerceflow.authservice.repository.UserAddressRepository;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ResourceNotFoundException;

/**
 * The address book, and mostly the default flag.
 *
 * <p>"At most one default" is easy. The cases that actually bite are the two ends: a customer with
 * one saved address and no default, and a customer who deletes the default and is left with none.
 * Both look harmless in the database and both surface as a checkout form that pre-selects nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressBookServiceTest {

    @Mock
    private UserAddressRepository repository;

    private AddressBookService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new AddressBookService(repository, new AuthProperties());
        userId = UUID.randomUUID();
        when(repository.save(any(UserAddress.class))).thenAnswer(i -> i.getArgument(0));
        when(repository.saveAndFlush(any(UserAddress.class))).thenAnswer(i -> i.getArgument(0));
    }

    private AddressRequest request(Boolean makeDefault) {
        return new AddressRequest("Home", AddressType.BOTH, "Ada Lovelace", "+31 20 123 4567",
                "Keizersgracht 1", null, "Amsterdam", "Noord-Holland", "1015 CJ", "nl",
                makeDefault);
    }

    private UserAddress stored(boolean isDefault) {
        return UserAddress.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .recipientName("Ada Lovelace")
                .line1("Keizersgracht 1")
                .city("Amsterdam")
                .countryCode("NL")
                .isDefault(isDefault)
                .build();
    }

    @Test
    @DisplayName("the first address saved is the default, whether or not it was asked for")
    void firstAddressBecomesDefault() {
        when(repository.countByUserId(userId)).thenReturn(0L);

        AddressResponse saved = service.create(request(null), userId);

        // Not a nicety. One saved address and nothing pre-selected is a checkout form asking a
        // customer to choose between one option.
        assertThat(saved.isDefault()).isTrue();
    }

    @Test
    @DisplayName("a later address is not the default unless the customer says so")
    void laterAddressIsNotDefaultByAccident() {
        when(repository.countByUserId(userId)).thenReturn(3L);

        assertThat(service.create(request(null), userId).isDefault()).isFalse();
        verify(repository, never()).clearOtherDefaults(any(), any());
    }

    @Test
    @DisplayName("promoting an address demotes whichever held the flag")
    void promotingDemotesTheOther() {
        UserAddress address = stored(false);
        when(repository.findByIdAndUserId(address.getId(), userId))
                .thenReturn(Optional.of(address));

        AddressResponse result = service.makeDefault(address.getId(), userId);

        assertThat(result.isDefault()).isTrue();
        verify(repository).clearOtherDefaults(userId, address.getId());
    }

    @Test
    @DisplayName("deleting the default promotes another address")
    void deletingTheDefaultPromotesASuccessor() {
        UserAddress going = stored(true);
        UserAddress remaining = stored(false);
        when(repository.findByIdAndUserId(going.getId(), userId)).thenReturn(Optional.of(going));
        when(repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId))
                .thenReturn(List.of(remaining));

        service.delete(going.getId(), userId);

        // Somebody has to be the default. A customer with three addresses and none of them
        // flagged is a state the checkout form has no sensible behaviour for.
        assertThat(remaining.isDefault()).isTrue();
        verify(repository).save(remaining);
    }

    @Test
    @DisplayName("deleting a non-default address promotes nobody")
    void deletingASpareChangesNothing() {
        UserAddress going = stored(false);
        when(repository.findByIdAndUserId(going.getId(), userId)).thenReturn(Optional.of(going));

        service.delete(going.getId(), userId);

        verify(repository, never()).findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId);
    }

    @Test
    @DisplayName("deleting the last address leaves no default and does not fall over")
    void deletingTheOnlyAddressIsFine() {
        UserAddress going = stored(true);
        when(repository.findByIdAndUserId(going.getId(), userId)).thenReturn(Optional.of(going));
        when(repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)).thenReturn(List.of());

        service.delete(going.getId(), userId);

        verify(repository, never()).save(any(UserAddress.class));
    }

    @Test
    @DisplayName("another customer's address is not found rather than forbidden")
    void otherPeoplesAddressesAreInvisible() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(eq(id), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the country code is upper-cased so tax and shipping can match on it")
    void countryCodeIsNormalised() {
        when(repository.countByUserId(userId)).thenReturn(0L);

        // Sent as "nl". A lower-case code matches no tax rate and no shipping zone, and the
        // failure is a wrong total rather than an error.
        assertThat(service.create(request(null), userId).countryCode()).isEqualTo("NL");
    }

    @Test
    @DisplayName("the formatted line drops the parts this address does not have")
    void formattingSkipsMissingParts() {
        when(repository.countByUserId(userId)).thenReturn(0L);

        String formatted = service.create(request(null), userId).formatted();

        assertThat(formatted)
                .isEqualTo("Ada Lovelace, Keizersgracht 1, 1015 CJ Amsterdam, Noord-Holland, NL")
                .doesNotContain(", ,");
    }

    @Test
    @DisplayName("the per-customer cap is enforced")
    void addressCountIsCapped() {
        AuthProperties tight = new AuthProperties();
        tight.setMaxAddressesPerUser(2);
        AddressBookService capped = new AddressBookService(repository, tight);
        when(repository.countByUserId(userId)).thenReturn(2L);

        assertThatThrownBy(() -> capped.create(request(null), userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most 2");
    }
}
