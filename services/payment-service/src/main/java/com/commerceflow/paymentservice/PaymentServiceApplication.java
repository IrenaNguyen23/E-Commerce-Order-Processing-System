package com.commerceflow.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Payment Service — charges the customer once stock has been reserved.
 *
 * <p>Its {@code payment.completed} / {@code payment.failed} outcome is what decides whether the
 * order completes or the saga compensates.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
