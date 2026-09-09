package com.commerceflow.orderservice.fulfilment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One thing that happened to a parcel.
 *
 * <p>Append-only. Nothing here is ever updated, which is what makes it usable as an answer to
 * "what actually happened" rather than "what does the system currently think".
 *
 * <p>{@link #recordedBy} is null for anything that came from a carrier rather than from a person,
 * which is the distinction that matters when a customer and a courier disagree.
 */
@Entity
@Table(name = "shipment_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShipmentStatus status;

    /** What a customer is shown: "Left our warehouse", "Nobody home, card left". */
    @Column(name = "note", length = 500)
    private String note;

    /** Where it was, when the carrier says where it was. */
    @Column(name = "location", length = 150)
    private String location;

    /** The operator who recorded it, or null when it came from a carrier. */
    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
