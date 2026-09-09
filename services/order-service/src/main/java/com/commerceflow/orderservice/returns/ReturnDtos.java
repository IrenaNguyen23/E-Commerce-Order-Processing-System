package com.commerceflow.orderservice.returns;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The request and response shapes for returns.
 *
 * <p>Gathered into one file because they are small, they are only ever used together, and a
 * package of eight one-record files makes the flow harder to read rather than easier.
 */
public final class ReturnDtos {

    private ReturnDtos() {
        throw new AssertionError("No instances");
    }

    /** What a customer sends to start a return. */
    @Schema(description = "A request to send items back")
    public record CreateReturnRequest(

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Which lines are coming back, and how many of each")
            @NotEmpty(message = "choose at least one item to return")
            @Size(max = 50)
            @Valid
            List<ReturnItemRequest> items,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Why, in the customer's own words. Kept verbatim: \"arrived "
                            + "cracked\" and \"changed my mind\" need different handling, and a "
                            + "dropdown always lacks the case in front of you.",
                    example = "The screen was cracked when it arrived")
            @NotBlank(message = "a reason is required")
            @Size(max = 500)
            String reason) {
    }

    @Schema(description = "One line coming back")
    public record ReturnItemRequest(

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The order line, not the product: an order can carry the same "
                            + "product on two lines and they may be returned separately")
            @NotNull(message = "orderItemId is required")
            UUID orderItemId,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
            @NotNull(message = "quantity is required")
            @Min(value = 1, message = "returning zero of something is not a return")
            Integer quantity) {
    }

    /** An operator's decision. */
    @Schema(description = "Approving or refusing a return")
    public record ReturnDecisionRequest(

            @Schema(description = "Shown to the customer. On a refusal it is the only place they "
                            + "will find out why, so it is worth writing.",
                    example = "Returned outside the 14 day window")
            @Size(max = 500)
            String note) {
    }

    /** What the person who opened the box decided. */
    @Schema(description = "Receiving returned goods")
    public record ReturnReceiptRequest(

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "true puts the units back on sale; false records them as "
                            + "received and written off. There is no default, on purpose: "
                            + "guessing wrong either sells somebody a broken thing or throws "
                            + "away stock that was fine.")
            @NotNull(message = "say whether these go back on sale")
            Boolean restock) {
    }

    @Schema(description = "A return request")
    public record ReturnResponse(
            UUID id,
            UUID orderId,
            String orderNumber,
            ReturnStatus status,
            String reason,
            BigDecimal refundAmount,
            String currency,

            @Schema(description = "Whether the original delivery charge is included. True only "
                    + "when the whole order came back.")
            boolean refundShipping,

            List<ReturnItemResponse> items,
            Instant requestedAt,
            Instant decidedAt,
            String decisionNote,
            Instant receivedAt,

            @Schema(description = "Whether the goods went back on sale. Null until they arrive.")
            Boolean restocked,

            Instant refundedAt,
            String refundReference,

            @Schema(description = "Why the last refund attempt failed, when one did. The return "
                    + "is back in RECEIVED and can be tried again.")
            String refundFailure) {

        static ReturnResponse of(ReturnRequest request) {
            return new ReturnResponse(
                    request.getId(),
                    request.getOrderId(),
                    request.getOrderNumber(),
                    request.getStatus(),
                    request.getReason(),
                    request.getRefundAmount(),
                    request.getCurrency(),
                    request.isRefundShipping(),
                    request.getItems().stream().map(ReturnItemResponse::of).toList(),
                    request.getRequestedAt(),
                    request.getDecidedAt(),
                    request.getDecisionNote(),
                    request.getReceivedAt(),
                    request.getRestocked(),
                    request.getRefundedAt(),
                    request.getRefundReference(),
                    request.getRefundFailure());
        }
    }

    @Schema(description = "One line of a return")
    public record ReturnItemResponse(
            UUID orderItemId,
            UUID productId,
            String productName,
            String sku,
            int quantity,
            BigDecimal refundAmount) {

        static ReturnItemResponse of(ReturnRequestItem item) {
            return new ReturnItemResponse(
                    item.getOrderItemId(),
                    item.getProductId(),
                    item.getProductName(),
                    item.getSku(),
                    item.getQuantity(),
                    item.getRefundAmount());
        }
    }

    /**
     * What of an order may still be sent back.
     *
     * <p>Served to the customer before they choose, so the form cannot offer a quantity that will
     * be refused — asking somebody to guess and then rejecting them is a worse experience than
     * showing them the answer.
     */
    @Schema(description = "What is still returnable on an order")
    public record ReturnableLine(
            UUID orderItemId,
            UUID productId,
            String productName,
            String sku,
            int orderedQuantity,

            @Schema(description = "Already claimed by an open or settled return")
            int alreadyReturned,

            @Schema(description = "orderedQuantity minus alreadyReturned")
            int returnableQuantity,

            @Schema(description = "What one unit gives back, tax included")
            BigDecimal refundPerUnit,

            String currency) {
    }
}
