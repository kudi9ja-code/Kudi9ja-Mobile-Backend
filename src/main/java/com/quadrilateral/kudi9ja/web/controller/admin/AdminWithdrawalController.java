package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalService;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalStatus;
import com.quadrilateral.kudi9ja.web.dto.WalletDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Approving money out.
 *
 * <p>By the time a request reaches this queue the wallet has <b>already been
 * debited</b> and a pending transaction is on the ledger. That happened at
 * request time so the same money could not be spent while an admin was looking
 * at it, and it changes what these two buttons mean:
 *
 * <ul>
 *   <li><b>Approve</b> settles the pending transaction. The money then leaves
 *       the collection account for the customer's own bank, which is a treasury
 *       action outside this system — recording the payout reference here is
 *       what ties the two together.
 *   <li><b>Decline</b> reverses the transaction and <b>refunds in full</b>. The
 *       ledger is append-only, so the row is not deleted: it becomes a reversal
 *       with a reason, and the customer can see what happened rather than
 *       finding a gap where their money was.
 * </ul>
 *
 * <p>There is no destination to choose and no way to change one. Money goes to
 * the payout account on the customer's own record, resolved and name-matched
 * when it was set — the Terms promise payouts only to an account in the
 * customer's own name, and an admin who could redirect one could break that.
 */
@RestController
@RequestMapping("/api/v1/admin/withdrawals")
@Tag(name = "Admin — withdrawals", description = "Approving and declining payouts")
public class AdminWithdrawalController {

    private final WithdrawalService withdrawals;
    private final AdminAccessService access;

    public AdminWithdrawalController(WithdrawalService withdrawals, AdminAccessService access) {
        this.withdrawals = withdrawals;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "The withdrawal queue, filterable by status")
    public PageResponse<WalletDtos.AdminWithdrawalResponse> queue(
            @RequestParam(defaultValue = "PENDING") WithdrawalStatus status,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        access.requireCanView();
        return PageResponse.of(
                withdrawals.queue(status, query, PageRequest.of(page, Math.min(size, 100))),
                withdrawals::toAdminResponse);
    }

    /**
     * Approves a payout.
     *
     * @param request carries the reference from the bank transfer that pays it.
     *                It is what lets a customer's question six months later be
     *                answered with a fact rather than a recollection.
     */
    @PostMapping("/{withdrawalId}/approve")
    @Operation(summary = "Approve. Settles the pending transaction.")
    public WalletDtos.AdminWithdrawalResponse approve(
            @PathVariable UUID withdrawalId,
            @RequestBody(required = false) WalletDtos.ApproveWithdrawalRequest request) {

        AdminUser actor = access.requireCanApprovePayments();
        return withdrawals.toAdminResponse(withdrawals.approve(
                withdrawalId,
                actorOf(actor),
                request == null ? null : request.payoutReference(),
                request == null ? null : request.note(),
                request != null && Boolean.TRUE.equals(request.paidByHand())));
    }

    /**
     * Declines a payout and refunds it.
     *
     * <p>The reason is mandatory and it reaches the customer. Their money went
     * out of their balance when they asked; being told nothing while it came
     * back would leave them watching a number move for no reason they can see.
     */
    @PostMapping("/{withdrawalId}/decline")
    @Operation(summary = "Decline. Reverses the transaction and refunds in full.")
    public WalletDtos.AdminWithdrawalResponse decline(
            @PathVariable UUID withdrawalId,
            @Valid @RequestBody WalletDtos.DeclineWithdrawalRequest request) {

        AdminUser actor = access.requireCanApprovePayments();
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw ApiException.validation(
                    "Say why this withdrawal is being declined. The customer is told the reason.");
        }
        return withdrawals.toAdminResponse(
                withdrawals.decline(withdrawalId, actorOf(actor), request.reason()));
    }

    private static AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
