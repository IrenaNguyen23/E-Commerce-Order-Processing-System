package com.commerceflow.gateway.config;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.exception.ErrorCode;

import reactor.core.publisher.Mono;

/**
 * Emits the platform error envelope for an unauthenticated request, so a 401 from the gateway is
 * indistinguishable in shape from a 401 raised inside a service.
 */
public class RestServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return ErrorEnvelopeWriter.write(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    /** Shared JSON writer for the two edge error handlers. */
    static final class ErrorEnvelopeWriter {

        private ErrorEnvelopeWriter() {
        }

        static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode code,
                                String message) {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

            String correlationId = exchange.getResponse().getHeaders()
                    .getFirst(CorrelationContext.HEADER);

            String body = """
                    {"success":false,"status":%d,"error":"%s","code":"%s","message":"%s",\
                    "path":"%s","correlationId":%s}"""
                    .formatted(status.value(), status.getReasonPhrase(), code.name(), message,
                            exchange.getRequest().getPath().value(),
                            correlationId == null ? "null" : "\"" + correlationId + "\"");

            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap(body.getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
    }
}
