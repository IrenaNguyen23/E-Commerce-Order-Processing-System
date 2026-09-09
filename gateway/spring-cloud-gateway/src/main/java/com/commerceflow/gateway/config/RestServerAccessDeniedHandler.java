package com.commerceflow.gateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;

import com.commerceflow.common.exception.ErrorCode;

import reactor.core.publisher.Mono;

/** Emits the platform error envelope for a request the caller is not allowed to make. */
public class RestServerAccessDeniedHandler implements ServerAccessDeniedHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return RestServerAuthenticationEntryPoint.ErrorEnvelopeWriter.write(
                exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                ErrorCode.FORBIDDEN.defaultMessage());
    }
}
