package com.commerceflow.orderservice.pricing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingRateRepository extends JpaRepository<ShippingRate, UUID> {

    Optional<ShippingRate> findByCountryCodeAndMethodAndActiveTrue(
            String countryCode, ShippingMethod method);

    /** Everything that can be offered for a destination, for the checkout form to display. */
    List<ShippingRate> findByCountryCodeAndActiveTrue(String countryCode);
}
