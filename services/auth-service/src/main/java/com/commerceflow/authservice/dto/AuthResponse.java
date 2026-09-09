package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Result of a successful login or refresh. */
@Schema(description = "Issued token pair")
public record AuthResponse(

        @Schema(description = "Short lived JWT to send as 'Authorization: Bearer ...'")
        String accessToken,

        @Schema(description = "Long lived token, single use: presenting it rotates the pair")
        String refreshToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(description = "Access token lifetime in seconds", example = "900")
        long expiresIn,

        UserResponse user) {

    public static AuthResponse of(String accessToken, String refreshToken, long expiresIn,
                                  UserResponse user) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
