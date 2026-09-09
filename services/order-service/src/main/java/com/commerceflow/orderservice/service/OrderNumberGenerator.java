package com.commerceflow.orderservice.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * Produces the human readable order reference, e.g. {@code CF-20260826-000123}.
 *
 * <p>The counter comes from a database sequence: monotonic, safe across replicas and cheap.
 * Customers quote this number, not the UUID.
 */
@Component
@RequiredArgsConstructor
public class OrderNumberGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderRepository orderRepository;
    private final OrderProperties properties;

    public String next() {
        long sequence = orderRepository.nextOrderSequence();
        String day = LocalDate.now(ZoneOffset.UTC).format(DAY);
        return "%s-%s-%06d".formatted(properties.getNumberPrefix(), day, sequence);
    }
}
