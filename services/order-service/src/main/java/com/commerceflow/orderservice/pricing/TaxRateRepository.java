package com.commerceflow.orderservice.pricing;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    /**
     * Every rate for a country, category rows and the standard rate together.
     *
     * <p>One query rather than one per line. A basket of twenty items would otherwise be twenty
     * lookups of a table with a few dozen rows in it, and the caller sorts out which row wins.
     */
    List<TaxRate> findByCountryCode(String countryCode);
}
