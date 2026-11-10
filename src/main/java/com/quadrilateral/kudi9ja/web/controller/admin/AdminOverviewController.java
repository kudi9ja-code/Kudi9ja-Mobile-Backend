package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.admin.AdminOverviewService;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The panel's front page, and the record of everything done from it.
 *
 * <p>Both endpoints are open to <b>every</b> admin, viewers included. That is
 * deliberate for the audit log in particular: being watched is the point of
 * keeping one, and a panel that hid part of the record from part of the team
 * would defeat it. The log is append-only — there is no endpoint here or
 * anywhere else that edits or deletes an entry.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin — overview", description = "The panel's front page and the audit log")
public class AdminOverviewController {

    private final AdminOverviewService overview;

    public AdminOverviewController(AdminOverviewService overview) {
        this.overview = overview;
    }

    /**
     * What is waiting, what the book looks like, and who is on it.
     *
     * <p>Customer funds held leads, because it is the figure the collection
     * account statement is reconciled against.
     */
    @GetMapping("/overview")
    @Operation(summary = "Funds held, the queues, the book and the people")
    public AdminDtos.OverviewResponse overview() {
        return overview.overview();
    }

    /** Every loan and wallet the Overview figures are added up from, so each tile opens its own list. */
    @GetMapping("/overview/book")
    @Operation(summary = "The rows behind the Overview figures")
    public AdminDtos.BookDetail book() {
        return overview.bookDetail();
    }

    @GetMapping("/audit")
    @Operation(summary = "The audit log. Append-only, readable by every admin.")
    public PageResponse<AdminDtos.AuditRow> audit(
            @RequestParam(required = false) AuditCategory category,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return PageResponse.of(
                overview.audit(category, subjectId, from, to, query,
                        PageRequest.of(page, Math.min(size, 200))),
                AdminDtos.AuditRow::from);
    }
}
