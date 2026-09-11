package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplicationService;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplicationStatus;
import com.quadrilateral.kudi9ja.web.dto.LoanApplicationDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deciding who gets lent to.
 *
 * <p>This is where money is created out of a promise, so it is the most
 * consequential thing in the panel. Reading the file is the job: a statement
 * that matches what was claimed, premises that look like the business
 * described, two guarantors who can actually be reached.
 *
 * <p>Approving <b>disburses immediately</b> — the loan is written and the
 * wallet credited in the same transaction, through the same path that has
 * always written a loan. There is no second step and no undo; the borrower's
 * own change-of-mind window is the only way back, exactly as before.
 *
 * <p>Declining <b>requires a reason</b>, and the customer is shown it word for
 * word. That is not a formality. A refusal somebody can act on brings them back
 * with a better application; a refusal they cannot understand loses a customer
 * and teaches them nothing.
 *
 * <p>Both need {@code canActOnLoans}, checked here and re-checked nowhere else
 * — which is why it is checked here on every call rather than trusted from the
 * screen that drew the button.
 */
@RestController
@RequestMapping("/api/v1/admin/loan-applications")
@Tag(name = "Admin — borrowing", description = "Reading applications and deciding them")
public class AdminLoanApplicationController {

    private final LoanApplicationService applications;
    private final AdminAccessService access;

    public AdminLoanApplicationController(
            LoanApplicationService applications, AdminAccessService access) {
        this.applications = applications;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "The application queue, filterable by status")
    public PageResponse<LoanApplicationDtos.AdminApplicationRow> queue(
            @RequestParam(required = false) LoanApplicationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        access.requireCanView();
        return PageResponse.of(
                applications.queue(status, PageRequest.of(page, Math.min(size, 100))),
                LoanApplicationDtos.AdminApplicationRow::from);
    }

    @GetMapping("/pending-count")
    @Operation(summary = "How many are waiting, for the badge on the queue")
    public Map<String, Long> pendingCount() {
        access.requireCanView();
        return Map.of("pending", applications.pendingCount());
    }

    /**
     * One application in full, with signed links to every document.
     *
     * <p>The links expire and are served through our own endpoint, so panel
     * access is re-checked when one is opened and the view is written to the
     * audit log. A bank statement says more about somebody than anything else
     * they will ever send us, and who looked at one is a question we have to be
     * able to answer.
     */
    @GetMapping("/{applicationId}")
    @Operation(summary = "One application, with signed links to the statement and photos")
    public LoanApplicationDtos.AdminApplicationDetail detail(@PathVariable UUID applicationId) {
        access.requireCanView();
        return LoanApplicationDtos.AdminApplicationDetail.from(
                applications.get(applicationId), applications::documentUrl);
    }

    @PostMapping("/{applicationId}/approve")
    @Operation(summary = "Approve and disburse. The money reaches the wallet immediately.")
    public LoanApplicationDtos.AdminApplicationDetail approve(
            @PathVariable UUID applicationId,
            @RequestBody(required = false) LoanApplicationDtos.ApproveApplicationRequest request) {

        AdminUser actor = access.requireCanActOnLoans();
        return LoanApplicationDtos.AdminApplicationDetail.from(
                applications.approve(
                        applicationId,
                        actorOf(actor),
                        request == null ? null : request.note()),
                applications::documentUrl);
    }

    @PostMapping("/{applicationId}/reject")
    @Operation(summary = "Decline, with a reason the customer is shown word for word")
    public LoanApplicationDtos.AdminApplicationDetail reject(
            @PathVariable UUID applicationId,
            @Valid @RequestBody LoanApplicationDtos.RejectApplicationRequest request) {

        AdminUser actor = access.requireCanActOnLoans();
        return LoanApplicationDtos.AdminApplicationDetail.from(
                applications.reject(applicationId, actorOf(actor), request.reason()),
                applications::documentUrl);
    }

    private static AuditService.Actor actorOf(AdminUser actor) {
        return new AuditService.Actor(actor.getUserId(), actor.getName(), actor.getEmail());
    }
}
