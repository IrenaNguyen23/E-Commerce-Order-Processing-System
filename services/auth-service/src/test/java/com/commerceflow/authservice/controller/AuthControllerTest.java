package com.commerceflow.authservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.commerceflow.authservice.dto.AuthResponse;
import com.commerceflow.authservice.dto.LoginRequest;
import com.commerceflow.authservice.dto.RegisterRequest;
import com.commerceflow.authservice.dto.UserResponse;
import com.commerceflow.authservice.service.AuthService;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web layer contract of {@code /api/auth}: routing, request validation and the shape of the
 * success and error envelopes. The security chain is disabled so the assertions stay focused
 * on the controller itself.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private com.commerceflow.authservice.service.AccountRecoveryService recoveryService;

    @MockitoBean
    private com.commerceflow.authservice.service.GuestSessionService guestSessionService;

    @Test
    @DisplayName("POST /api/auth/register returns 201 with the created account")
    void registerReturnsCreated() throws Exception {
        UserResponse created = new UserResponse(UUID.randomUUID(), "ada@commerceflow.io",
                "Ada Lovelace", null, Set.of("CUSTOMER"), true, Instant.now());
        when(authService.register(any(RegisterRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                "ada@commerceflow.io", "S3cret-pass", "Ada Lovelace", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("ada@commerceflow.io"))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"));
    }

    @Test
    @DisplayName("POST /api/auth/register rejects a malformed body with a field error list")
    void registerValidatesInput() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(
                                "not-an-email", "short", "", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        verify(authService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("POST /api/auth/login returns the issued token pair")
    void loginReturnsTokens() throws Exception {
        UserResponse user = new UserResponse(UUID.randomUUID(), "ada@commerceflow.io",
                "Ada Lovelace", null, Set.of("CUSTOMER"), true, Instant.now());
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenReturn(AuthResponse.of("access-token", "refresh-token", 900L, user));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ada@commerceflow.io", "S3cret-pass"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900));
    }

    @Test
    @DisplayName("bad credentials surface as a 401 in the platform error envelope")
    void loginFailureIsMappedTo401() throws Exception {
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenThrow(new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ada@commerceflow.io", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_CREDENTIALS.name()))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/auth/logout without a bearer token is a 401")
    void logoutRequiresBearerToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.name()));
    }
}
