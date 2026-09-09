package com.commerceflow.gateway.filter;

import java.util.UUID;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.commerceflow.common.context.CorrelationContext;

import reactor.core.publisher.Mono;

/**
 * Stamps every request with a correlation id before it leaves the edge.
 *
 * <p>The id is what stitches one customer action together across six services and an asynchronous
 * saga: it travels on the HTTP hop as a header, is copied onto the Kafka records by the outbox
 * relay, and appears in every structured log line.
 *
 * <p>A client supplied id is honoured, so a caller can correlate on their side too.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationContext.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String resolved = correlationId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(CorrelationContext.HEADER, resolved)
                .build();
        exchange.getResponse().getHeaders().set(CorrelationContext.HEADER, resolved);

        return chain.filter(exchange.mutate().request(request).build());
    }
}
