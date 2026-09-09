package com.commerceflow.authservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.commerceflow.authservice.repository.RefreshTokenRepository;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.testsupport.AbstractSagaIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The full account lifecycle over HTTP, against real PostgreSQL, Redis and Kafka.
 *
 * <p>Exercised through the API rather than the service, because the parts most likely to break
 * silently — the security filter chain, the JSON envelope, the Redis deny-list — only exist in
 * the HTTP path.
 */
class AuthFlowIntegrationTest extends AbstractSagaIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("register, login, read the profile, rotate the token, then log out")
    void fullAccountLifecycle() {
        String email = "ada+" + UUID.randomUUID() + "@commerceflow.io";

        // ---- register ------------------------------------------------------------------
        ResponseEntity<JsonNode> registered = post("/api/auth/register", """
                {"email":"%s","password":"S3cret-pass","fullName":"Ada Lovelace"}"""
                .formatted(email), null);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody().path("data").path("email").asText()).isEqualTo(email);
        assertThat(registered.getBody().path("data").has("passwordHash")).isFalse();

        // ---- the same address cannot be registered twice --------------------------------
        ResponseEntity<JsonNode> duplicate = post("/api/auth/register", """
                {"email":"%s","password":"S3cret-pass","fullName":"Ada Lovelace"}"""
                .formatted(email), null);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().path("code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");

        // ---- login ----------------------------------------------------------------------
        ResponseEntity<JsonNode> loggedIn = post("/api/auth/login", """
                {"email":"%s","password":"S3cret-pass"}""".formatted(email), null);

        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loggedIn.getBody().path("data").path("accessToken").asText();
        String refreshToken = loggedIn.getBody().path("data").path("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        UUID userId = UUID.fromString(loggedIn.getBody().path("data").path("user").path("id").asText());
        assertThat(refreshTokenRepository.countByUserIdAndRevokedFalse(userId)).isEqualTo(1);

        // ---- a wrong password is indistinguishable from an unknown account ---------------
        ResponseEntity<JsonNode> wrongPassword = post("/api/auth/login", """
                {"email":"%s","password":"wrong-password"}""".formatted(email), null);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().path("code").asText()).isEqualTo("INVALID_CREDENTIALS");

        // ---- the profile requires the token ---------------------------------------------
        assertThat(get("/api/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> profile = get("/api/auth/me", accessToken);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody().path("data").path("email").asText()).isEqualTo(email);

        // ---- refresh rotates the pair ----------------------------------------------------
        ResponseEntity<JsonNode> refreshed = post("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(refreshToken), null);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);

        String rotatedRefresh = refreshed.getBody().path("data").path("refreshToken").asText();
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);

        // ---- reusing the rotated token revokes every session -----------------------------
        ResponseEntity<JsonNode> reuse = post("/api/auth/refresh", """
                {"refreshToken":"%s"}""".formatted(refreshToken), null);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshTokenRepository.countByUserIdAndRevokedFalse(userId)).isZero();

        // ---- logout denies the access token for the rest of its life ---------------------
        ResponseEntity<JsonNode> secondLogin = post("/api/auth/login", """
                {"email":"%s","password":"S3cret-pass"}""".formatted(email), null);
        String freshAccessToken = secondLogin.getBody().path("data").path("accessToken").asText();

        assertThat(get("/api/auth/me", freshAccessToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/auth/logout", "", freshAccessToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        await().atMost(SAGA_TIMEOUT).untilAsserted(() ->
                assertThat(get("/api/auth/me", freshAccessToken).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("registration queues a welcome notification on notification.send")
    void registrationPublishesWelcomeNotification() {
        String email = "grace+" + UUID.randomUUID() + "@commerceflow.io";

        try (Consumer<String, Object> consumer = consumerFor(KafkaTopics.NOTIFICATION_SEND)) {
            ResponseEntity<JsonNode> registered = post("/api/auth/register", """
                    {"email":"%s","password":"S3cret-pass","fullName":"Grace Hopper"}"""
                    .formatted(email), null);
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            List<ConsumerRecord<String, Object>> records = drain(consumer, SAGA_TIMEOUT);

            assertThat(records).isNotEmpty();
            NotificationSendEvent event = (NotificationSendEvent) records.get(0).value();
            assertThat(event.getRecipient()).isEqualTo(email);
            assertThat(event.getTemplateCode()).isEqualTo("USER_WELCOME");
            assertThat(event.getParams()).containsEntry("fullName", "Grace Hopper");
        }
    }

    @Test
    @DisplayName("a malformed registration is rejected with a field error list")
    void validationFailureIsStructured() {
        ResponseEntity<JsonNode> response = post("/api/auth/register",
                """
                {"email":"not-an-email","password":"short","fullName":""}""", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().path("fieldErrors").isArray()).isTrue();
        assertThat(response.getBody().path("fieldErrors")).isNotEmpty();
    }

    private ResponseEntity<JsonNode> post(String path, String body, String bearerToken) {
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(bearerToken)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, String bearerToken) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(bearerToken)), JsonNode.class);
    }

    private static HttpHeaders headers(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }
}
