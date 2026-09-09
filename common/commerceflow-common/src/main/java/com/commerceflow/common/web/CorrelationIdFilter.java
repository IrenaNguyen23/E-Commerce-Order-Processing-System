package com.commerceflow.common.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import com.commerceflow.common.context.CorrelationContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Binds {@code X-Correlation-Id} to the logging MDC for the duration of the request and echoes
 * it back on the response, so a single customer action can be followed across all six services.
 *
 * <p>Runs before every other filter, including Spring Security's, so authentication failures are
 * logged with a correlation id too.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            CorrelationContext.set(request.getHeader(CorrelationContext.HEADER));
            String correlationId = CorrelationContext.getOrCreate();
            response.setHeader(CorrelationContext.HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
