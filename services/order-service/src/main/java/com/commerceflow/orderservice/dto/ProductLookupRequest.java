package com.commerceflow.orderservice.dto;

import java.util.List;
import java.util.UUID;

/** Body of the batch catalogue lookup, so pricing a basket costs one call instead of N. */
public record ProductLookupRequest(List<UUID> productIds) {
}
