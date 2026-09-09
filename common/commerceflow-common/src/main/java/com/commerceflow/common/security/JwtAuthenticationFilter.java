package com.commerceflow.common.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.commerceflow.common.exception.UnauthorizedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Populates the {@link SecurityContextHolder} from a {@code Bearer} access token.
 *
 * <p>Every service verifies the signature itself rather than trusting gateway headers, so a
 * request that bypasses the gateway inside the cluster is still rejected.
 *
 * <p>The filter never rejects a request on its own: an unauthenticated request simply reaches
 * the authorisation rules, which produce a consistent 401/403 through the configured
 * authentication entry point.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationChecker revocationChecker;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = JwtTokenProvider.resolveBearerToken(
                request.getHeader(SecurityHeaders.AUTHORIZATION));

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedUser user = tokenProvider.authenticate(token, TokenType.ACCESS);
                if (revocationChecker.isRevoked(user.tokenId())) {
                    log.debug("Rejected revoked token {}", user.tokenId());
                } else {
                    SecurityContextHolder.getContext().setAuthentication(toAuthentication(user, request));
                }
            } catch (UnauthorizedException ex) {
                log.debug("Continuing as anonymous: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken toAuthentication(AuthenticatedUser user,
                                                                 HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
