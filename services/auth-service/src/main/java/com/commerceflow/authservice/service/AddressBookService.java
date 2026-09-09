package com.commerceflow.authservice.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.authservice.config.AuthProperties;
import com.commerceflow.authservice.dto.AddressRequest;
import com.commerceflow.authservice.dto.AddressResponse;
import com.commerceflow.authservice.entity.AddressType;
import com.commerceflow.authservice.entity.UserAddress;
import com.commerceflow.authservice.repository.UserAddressRepository;
import com.commerceflow.common.address.PostalAddress;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A customer's saved addresses.
 *
 * <p>Every method takes the owner's id and every query is scoped by it. There is no "find by id
 * then check the owner" anywhere in this class, because that is the same query written in a form
 * that a later edit can quietly get wrong.
 *
 * <h2>The default address</h2>
 *
 * <p>At most one address per customer is the default, and exactly one is once they have saved
 * anything at all:
 *
 * <ul>
 *   <li>The <b>first</b> address a customer saves becomes the default whether they asked or not —
 *       a checkout form with a single saved address and nothing pre-selected is a form that makes
 *       people choose between one option.
 *   <li>Promoting an address demotes the previous one in the same transaction.
 *   <li>Deleting the default <b>promotes another address</b>. Leaving a customer with three saved
 *       addresses and no default is a state nothing else in the system expects.
 * </ul>
 *
 * <p>A partial unique index backs all of this up. The service handles the ordinary path; the index
 * is what turns two simultaneous "make this my default" requests into one failure rather than one
 * customer with two defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressBookService {

    private final UserAddressRepository repository;
    private final AuthProperties properties;

    @Transactional(readOnly = true)
    public List<AddressResponse> list(UUID userId) {
        return repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId).stream()
                .map(AddressBookService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse get(UUID id, UUID userId) {
        return toResponse(require(id, userId));
    }

    /**
     * Saves a new address.
     *
     * @throws BusinessException when the customer already has as many as they are allowed. The cap
     *     exists so an automated client cannot turn a profile into unbounded storage; it is
     *     generous enough that no real person will meet it.
     */
    @Transactional
    public AddressResponse create(AddressRequest request, UUID userId) {
        long existing = repository.countByUserId(userId);
        if (existing >= properties.getMaxAddressesPerUser()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "You can save at most " + properties.getMaxAddressesPerUser()
                            + " addresses. Delete one you no longer use first.");
        }

        UserAddress address = UserAddress.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .build();
        apply(address, request);

        // The first one is the default whether it was asked for or not.
        boolean makeDefault = existing == 0 || Boolean.TRUE.equals(request.makeDefault());
        address.setDefault(makeDefault);

        UserAddress saved = repository.save(address);
        if (makeDefault) {
            repository.clearOtherDefaults(userId, saved.getId());
        }

        log.info("Customer {} saved address {} ({})", userId, saved.getId(), saved.getCity());
        return toResponse(saved);
    }

    /** Replaces an address in place. The id survives, so a client holding it stays valid. */
    @Transactional
    public AddressResponse update(UUID id, AddressRequest request, UUID userId) {
        UserAddress address = require(id, userId);
        apply(address, request);

        if (Boolean.TRUE.equals(request.makeDefault())) {
            address.setDefault(true);
            repository.saveAndFlush(address);
            repository.clearOtherDefaults(userId, id);
        }
        return toResponse(repository.save(address));
    }

    /** Promotes one address and demotes whichever held the flag. */
    @Transactional
    public AddressResponse makeDefault(UUID id, UUID userId) {
        UserAddress address = require(id, userId);
        address.setDefault(true);
        repository.saveAndFlush(address);
        repository.clearOtherDefaults(userId, id);
        return toResponse(address);
    }

    /**
     * Deletes an address.
     *
     * <p>A hard delete, and that is only safe because no order points at this row — orders copy the
     * address at checkout. If they referenced it, this method would have to be an archive flag and
     * every query in the system would need to remember to filter on it.
     */
    @Transactional
    public void delete(UUID id, UUID userId) {
        UserAddress address = require(id, userId);
        boolean wasDefault = address.isDefault();
        repository.delete(address);
        repository.flush();

        if (wasDefault) {
            // Somebody has to be the default. Most recently touched is the best guess available,
            // and it is a guess the customer can override in one click.
            repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId).stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefault(true);
                        repository.save(next);
                        log.info("Customer {} deleted their default address; promoted {}",
                                userId, next.getId());
                    });
        }
    }

    private UserAddress require(UUID id, UUID userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Address not found: " + id));
    }

    private static void apply(UserAddress address, AddressRequest request) {
        address.setLabel(trimToNull(request.label()));
        address.setType(request.type() == null ? AddressType.BOTH : request.type());
        address.setRecipientName(request.recipientName().trim());
        address.setPhone(trimToNull(request.phone()));
        address.setLine1(request.line1().trim());
        address.setLine2(trimToNull(request.line2()));
        address.setCity(request.city().trim());
        address.setRegion(trimToNull(request.region()));
        address.setPostalCode(trimToNull(request.postalCode()));
        address.setCountryCode(request.countryCode().trim());
    }

    /** The shared {@link PostalAddress} does the formatting, so every service prints it alike. */
    static AddressResponse toResponse(UserAddress a) {
        PostalAddress postal = new PostalAddress(a.getRecipientName(), a.getPhone(), a.getLine1(),
                a.getLine2(), a.getCity(), a.getRegion(), a.getPostalCode(), a.getCountryCode());

        return new AddressResponse(a.getId(), a.getLabel(), a.getType(), a.getRecipientName(),
                a.getPhone(), a.getLine1(), a.getLine2(), a.getCity(), a.getRegion(),
                a.getPostalCode(), postal.country(), postal.formatted(), a.isDefault(),
                a.getCreatedAt(), a.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
