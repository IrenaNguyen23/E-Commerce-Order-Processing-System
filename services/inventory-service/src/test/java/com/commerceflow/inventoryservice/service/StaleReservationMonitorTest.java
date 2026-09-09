package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import com.commerceflow.inventoryservice.config.InventoryProperties;
import com.commerceflow.inventoryservice.entity.InventoryReservation;
import com.commerceflow.inventoryservice.entity.ReservationStatus;
import com.commerceflow.inventoryservice.repository.InventoryReservationRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The sweep exists to make stuck stock visible. These tests mostly pin down what it must
 * <em>not</em> do.
 *
 * <p>Releasing a stale hold automatically is the obvious next step and it is the wrong one: from
 * inside Inventory, a hold whose saga vanished is indistinguishable from one whose saga is parked
 * mid-payment, and the second may already have been charged. If someone later adds a release call
 * here, {@link #reportsWithoutReleasingAnything()} is what should stop them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleReservationMonitorTest {

    @Mock
    private InventoryReservationRepository reservationRepository;

    private StaleReservationMonitor monitor;
    private InventoryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new InventoryProperties();
        monitor = new StaleReservationMonitor(reservationRepository, properties,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("counts the stale holds and reports them without touching any stock")
    void reportsWithoutReleasingAnything() {
        when(reservationRepository.countByStatusAndCreatedAtBefore(
                eq(ReservationStatus.RESERVED), any(Instant.class))).thenReturn(3L);
        when(reservationRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(ReservationStatus.RESERVED), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(stale("CF-1"), stale("CF-2"), stale("CF-3")));

        monitor.reportStaleReservations();

        assertThat(monitor.staleCount()).isEqualTo(3);

        // The whole point. Nothing is saved, nothing is released, no event goes out — a person
        // decides, because only a person can find out whether the order was paid for.
        verify(reservationRepository, never()).save(any(InventoryReservation.class));
        verify(reservationRepository, never()).delete(any(InventoryReservation.class));
    }

    @Test
    @DisplayName("a clean sweep reports nothing and does not query for a sample")
    void quietWhenNothingIsStale() {
        when(reservationRepository.countByStatusAndCreatedAtBefore(
                any(ReservationStatus.class), any(Instant.class))).thenReturn(0L);

        monitor.reportStaleReservations();

        assertThat(monitor.staleCount()).isZero();
        verify(reservationRepository, never())
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        any(ReservationStatus.class), any(Instant.class), any(Pageable.class));
    }

    @Test
    @DisplayName("only holds still carrying stock are counted")
    void onlyReservedHoldsCount() {
        when(reservationRepository.countByStatusAndCreatedAtBefore(
                any(ReservationStatus.class), any(Instant.class))).thenReturn(1L);
        when(reservationRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                any(ReservationStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(stale("CF-1")));

        monitor.reportStaleReservations();

        // RELEASED and CONFIRMED holds are old too, and neither is holding anything. Counting
        // them would make the gauge climb forever and the alert meaningless.
        verify(reservationRepository).countByStatusAndCreatedAtBefore(
                eq(ReservationStatus.RESERVED), any(Instant.class));
    }

    @Test
    @DisplayName("a failing sweep does not take the service down with it")
    void sweepFailureIsContained() {
        when(reservationRepository.countByStatusAndCreatedAtBefore(
                any(ReservationStatus.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("connection reset"));

        // Housekeeping. The next pass ten minutes later reports the same thing.
        monitor.reportStaleReservations();

        assertThat(monitor.staleCount()).isZero();
    }

    private static InventoryReservation stale(String orderNumber) {
        return InventoryReservation.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .orderNumber(orderNumber)
                .status(ReservationStatus.RESERVED)
                .createdAt(Instant.now().minusSeconds(60 * 60 * 6))
                .updatedAt(Instant.now().minusSeconds(60 * 60 * 6))
                .items(new ArrayList<>())
                .build();
    }
}
