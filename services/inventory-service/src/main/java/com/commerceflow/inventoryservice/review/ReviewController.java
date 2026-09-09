package com.commerceflow.inventoryservice.review;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Product reviews.
 *
 * <p>Reading published reviews is public — they are part of the product page. Writing one requires
 * an account, and by default a moderator has to approve it before anyone else sees it.
 */
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "What customers thought")
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/products/{productId}/reviews")
    @Operation(summary = "Published reviews of a product",
            description = "Newest first. Pending and rejected reviews are visible only to their "
                    + "own author and to administrators.")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> forProduct(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {

        return ResponseEntity.ok(ApiResponse.ok(reviewService.forProduct(productId, page, size)));
    }

    @PutMapping("/products/{productId}/reviews")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Write or replace my review",
            description = """
                    One review per customer per product, so submitting again edits the one you \
                    already wrote rather than adding a second.

                    **An edit goes back into moderation.** Otherwise the queue is defeated by \
                    submitting something bland, waiting for approval, and then editing it into \
                    whatever you actually wanted to publish.""")
    public ResponseEntity<ApiResponse<ReviewResponse>> submit(
            @PathVariable UUID productId,
            @Valid @RequestBody ReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser author) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                reviewService.submit(productId, request, require(author)),
                "Thanks — your review has been received"));
    }

    @GetMapping("/reviews/mine")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Everything I have written",
            description = "Including anything still waiting for moderation, and anything "
                    + "rejected — with the reason, which is the only place it is shown.")
    public ResponseEntity<ApiResponse<List<ReviewResponse>>> mine(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(ApiResponse.ok(reviewService.mine(require(caller).userId())));
    }

    @GetMapping("/reviews/pending")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "The moderation queue", description = "Oldest first.")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> pending(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(ApiResponse.ok(reviewService.pending(page, size)));
    }

    @PostMapping("/reviews/{reviewId}/moderate")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Publish or reject a review",
            description = "A rejected review is kept rather than deleted, so its author can be "
                    + "told why it is not showing and the decision stays auditable.")
    public ResponseEntity<ApiResponse<ReviewResponse>> moderate(
            @PathVariable UUID reviewId,
            @RequestParam boolean publish,
            @RequestParam(required = false) String note,
            @AuthenticationPrincipal AuthenticatedUser moderator) {

        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.moderate(reviewId, publish, note, require(moderator)),
                publish ? "Review published" : "Review rejected"));
    }

    @DeleteMapping("/reviews/{reviewId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a review",
            description = "Your own, or anyone's if you are an administrator. Somebody else's "
                    + "returns 404 rather than 403 — \"this exists but is not yours\" is still an "
                    + "answer about it.")
    public ResponseEntity<Void> delete(
            @PathVariable UUID reviewId, @AuthenticationPrincipal AuthenticatedUser caller) {

        reviewService.delete(reviewId, require(caller));
        return ResponseEntity.noContent().build();
    }

    private static AuthenticatedUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return caller;
    }
}
