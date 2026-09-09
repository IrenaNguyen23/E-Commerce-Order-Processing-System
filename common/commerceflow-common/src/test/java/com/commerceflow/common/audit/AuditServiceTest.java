package com.commerceflow.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.commerceflow.common.security.AuthenticatedUser;

/**
 * The audit trail.
 *
 * <p>Most of this is about the service refusing to be the reason a request fails, while still
 * being written inside the transaction it describes. Those two pull against each other, and the
 * tests below pin how the tension was resolved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    @Mock
    private AuditRepository repository;

    @Captor
    private ArgumentCaptor<AuditEntry> entryCaptor;

    private AuditService service;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        service = new AuditService(repository);
        admin = new AuthenticatedUser(UUID.randomUUID(), "ops@commerceflow.io",
                Set.of("ADMIN"), UUID.randomUUID().toString());
        when(repository.save(any(AuditEntry.class))).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        // Leaking a principal between tests would make the "no principal" case pass for the
        // wrong reason, which is the kind of green that hides a real failure.
        SecurityContextHolder.clearContext();
    }

    private void signIn(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }

    @Test
    @DisplayName("the actor comes from the security context without being passed in")
    void actorComesFromTheContext() {
        signIn(admin);

        service.record("ORDER_CANCELLED", "ORDER", "abc", "Cancelled it");

        verify(repository).save(entryCaptor.capture());
        // The point of reading the context: adding an audit line to a new endpoint is one
        // statement, not a signature change and a dozen call sites.
        assertThat(entryCaptor.getValue().getActorId()).isEqualTo(admin.userId());
        assertThat(entryCaptor.getValue().getActorEmail()).isEqualTo("ops@commerceflow.io");
    }

    @Test
    @DisplayName("no principal records an unattributed entry rather than throwing")
    void noPrincipalIsStillRecorded() {
        service.record("RESERVATION_SWEPT", "RESERVATION", "abc", "Swept by the scheduler");

        verify(repository).save(entryCaptor.capture());
        // A scheduled job has nobody behind it. An entry saying "the system" is more use than
        // an exception thrown out of a housekeeping task.
        assertThat(entryCaptor.getValue().getActorId()).isNull();
    }

    @Test
    @DisplayName("an unexpected principal type does not break the write")
    void foreignPrincipalIsTolerated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("some-string-principal", null,
                        java.util.List.of()));

        service.record("PRODUCT_UPDATED", "PRODUCT", "abc", "Changed the price");

        verify(repository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getActorId()).isNull();
    }

    @Test
    @DisplayName("an explicit actor beats the context")
    void explicitActorWins() {
        AuthenticatedUser other = new AuthenticatedUser(UUID.randomUUID(), "other@example.com",
                Set.of("ADMIN"), UUID.randomUUID().toString());
        signIn(admin);

        service.record(other, "ROLES_CHANGED", "USER", "abc", "Changed roles");

        verify(repository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getActorEmail()).isEqualTo("other@example.com");
    }

    @Test
    @DisplayName("an over-long summary is truncated, not refused")
    void longSummaryIsTruncated() {
        signIn(admin);

        service.record("PRODUCT_UPDATED", "PRODUCT", "abc", "x".repeat(2000));

        verify(repository).save(entryCaptor.capture());
        // Refusing would fail the operation being audited, which is exactly backwards: the audit
        // trail exists to serve the change, not to veto it.
        assertThat(entryCaptor.getValue().getSummary()).hasSizeLessThanOrEqualTo(500);
        assertThat(entryCaptor.getValue().getSummary()).endsWith("...");
    }

    @Test
    @DisplayName("a summary that fits is left exactly as written")
    void shortSummaryIsUntouched() {
        signIn(admin);

        service.record("ORDER_CANCELLED", "ORDER", "abc", "Cancelled at the customer's request");

        verify(repository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getSummary())
                .isEqualTo("Cancelled at the customer's request");
    }

    @Test
    @DisplayName("a null target id is allowed rather than becoming the string \"null\"")
    void nullTargetStaysNull() {
        signIn(admin);

        service.record("BULK_EXPORT", "REPORT", null, "Exported everything");

        verify(repository).save(entryCaptor.capture());
        // String.valueOf(null) is "null", which would read as a real id in a table somebody is
        // scanning during an incident.
        assertThat(entryCaptor.getValue().getTargetId()).isNull();
    }

    @Test
    @DisplayName("the entry carries when it happened")
    void occurredAtIsSet() {
        signIn(admin);

        service.record("ORDER_CANCELLED", "ORDER", "abc", "Cancelled it");

        verify(repository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getOccurredAt()).isNotNull();
        assertThat(entryCaptor.getValue().getId()).isNotNull();
    }
}
