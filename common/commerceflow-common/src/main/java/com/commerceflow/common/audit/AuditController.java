package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.audit.AuditQueryService.AuditSearch;
import com.commerceflow.common.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Reading this service's audit trail.
 *
 * <h2>One controller, five services</h2>
 *
 * <p>This class lives in the shared library and is contributed by
 * {@code CommonAuditAutoConfiguration}, so every service that has an {@code admin_audit_log} gets
 * the endpoint without a line of its own code — the same arrangement as the writing side.
 *
 * <p>Each instance answers only for its own database. The back office asks all five and merges the
 * answers; see {@link AuditQueryService} for why the log is not centralised.
 *
 * <h2>Administrators only, and read-only</h2>
 *
 * <p>There is no endpoint here that writes, amends or deletes an entry, and there will not be one.
 * A trail that can be edited through the API it is read through answers a different question from
 * the one anybody asks it.
 *
 * <p>Worth stating plainly: an administrator can read the record of their own actions. That is
 * correct — the point of the trail is that the action was recorded, not that it was hidden from
 * the person who took it. Separating "may administer" from "may audit" needs a role that does not
 * exist yet, and is noted as a limitation rather than half-built here.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Audit", description = "What operators did, and when")
public class AuditController {

    private final AuditQueryService queryService;

    @GetMapping
    @Operation(summary = "Search this service's audit trail",
            description = """
                    Newest first. Every filter is optional; with none, it is simply the most \
                    recent activity in this service.

                    **Paged by position, not by page number.** The response carries \
                    `nextBeforeAt` and `nextBeforeId`; send them back as `beforeAt` and \
                    `beforeId` for the following page. Offsets are not offered on purpose: the \
                    back office merges this stream with the same stream from four other \
                    services, and page 3 of one service does not cover the same span of time as \
                    page 3 of another.

                    There is no total count. Producing one would mean a second query over a \
                    table with a two-year retention, on every request, for a number nobody acts \
                    on — `hasMore` answers what the screen needs.""")
    public ResponseEntity<ApiResponse<AuditSlice>> search(

            @Parameter(description = "Part of the acting account's email address")
            @RequestParam(required = false) String actor,

            @Parameter(description = "Exact action, e.g. ROLES_CHANGED. See /api/audit/actions")
            @RequestParam(required = false) String action,

            @Parameter(description = "ORDER, PRODUCT, USER, COUPON, REVIEW, WAREHOUSE")
            @RequestParam(required = false) String targetType,

            @Parameter(description = "Everything that ever happened to one thing")
            @RequestParam(required = false) String targetId,

            @Parameter(description = "One customer action, across every service that recorded it")
            @RequestParam(required = false) String correlationId,

            @Parameter(description = "Inclusive lower bound, ISO-8601")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Inclusive upper bound, ISO-8601")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,

            @Parameter(description = "Position to continue from — nextBeforeAt of the previous page")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeAt,

            @Parameter(description = "Tiebreak for entries sharing beforeAt — nextBeforeId")
            @RequestParam(required = false) UUID beforeId,

            @RequestParam(defaultValue = "50") @Min(1) @Max(AuditQueryService.MAX_PAGE_SIZE)
            int size) {

        AuditSlice slice = queryService.search(new AuditSearch(
                actor, action, targetType, targetId, correlationId,
                from, to, beforeAt, beforeId, size));

        return ResponseEntity.ok(ApiResponse.ok(slice));
    }

    @GetMapping("/actions")
    @Operation(summary = "Actions this service has recorded",
            description = "What is actually in the table, so the filter offers real values "
                    + "rather than a hard-coded list that drifts every time a new operation "
                    + "starts being audited.")
    public ResponseEntity<ApiResponse<List<String>>> actions() {
        return ResponseEntity.ok(ApiResponse.ok(queryService.actions()));
    }
}
