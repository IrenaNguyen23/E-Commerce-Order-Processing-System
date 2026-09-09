package com.commerceflow.gateway.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.exception.ErrorCode;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Where a tripped circuit breaker lands.
 *
 * <p>Returns 503 in the platform error envelope rather than letting the caller see a raw gateway
 * timeout, so a client can distinguish "this service is down, retry later" from "your request was
 * wrong" without parsing a stack trace.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping(value = "/{service}", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    public Mono<ResponseEntity<Map<String, Object>>> fallback(@PathVariable String service,
                                                              ServerWebExchange exchange) {
        String correlationId = exchange.getResponse().getHeaders()
                .getFirst(CorrelationContext.HEADER);
        log.error("Circuit breaker open for {} (correlationId={})", service, correlationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        body.put("code", ErrorCode.SERVICE_UNAVAILABLE.name());
        body.put("message", "The " + service + " service is temporarily unavailable, please retry");
        body.put("path", exchange.getRequest().getPath().value());
        body.put("correlationId", correlationId);

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}
