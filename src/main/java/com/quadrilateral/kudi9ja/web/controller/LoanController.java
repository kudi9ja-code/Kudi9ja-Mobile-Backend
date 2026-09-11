package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.common.idempotency.IdempotencyService;
import com.quadrilateral.kudi9ja.domain.loan.CreditScoreService;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.LoanDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrowing.
 *
 * <p><b>The client never prices a loan.</b> Every figure a customer sees —
 * what they are offered, what it will cost, what the schedule looks like — comes
 * from {@code /quote} and {@code /eligibility}, computed by the same code that
 * will price the loan itself. The Flutter app used to work these out on the
 * device, and a device that can price a loan is a device that can price itself
 * a better one.
 *
 * <p>Interest is <b>flat</b>: computed once on the principal, never compounding
 * and never growing. Every month from 1 to 24 has its own rate, and the rate a
 * loan is priced at is frozen onto it at disbursement — a later change to the
 * rate card never rewrites a running loan.
 *
 * <p>Three things this controller deliberately has no endpoint for, because
 * they do not exist in the product: a late fee, penalty interest, and any
 * charge for settling early. The Lending Agreement commits to all three in
 * writing — <i>"even in default, the amount you owe does not increase"</i> —
 * and settling early earns a <b>rebate</b> rather than a penalty.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Lending", description = "Quotes, eligibility, loans and repayments")
public class LoanController {

    private final LoanService loans;
    private final CreditScoreService creditScores;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;

    public LoanController(
            LoanService loans,
            CreditScoreService creditScores,
            IdempotencyService idempotency,
            CurrentUser currentUser) {
        this.loans = loans;
        this.creditScores = creditScores;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
    }

    /**
     * What a loan of this size over this tenure would cost.
     *
     * <p>Includes the management fee, which is <b>deducted from the
     * disbursement and never added to the debt</b>: a customer borrowing
     * ₦500,000 owes interest on ₦500,000 and receives ₦495,000.
     */
    @GetMapping("/loans/quote")
    @Operation(summary = "Fee, net disbursement, interest, total and the full schedule")
    public LoanDtos.QuoteResponse quote(
            @RequestParam BigDecimal amount,
            @RequestParam int months) {
        return loans.quote(amount, months);
    }

    /**
     * What this customer is offered, and why.
     *
     * <p>The offer is built from what they have done with Kudi9ja — what they
     * have saved, what they have repaid, whether they are verified — and capped
     * by the headroom their open loans leave. Nothing is given away: there is no
     * sign-up bonus and no free credit anywhere in this product.
     */
    @GetMapping("/loans/eligibility")
    @Operation(summary = "The offer, the headroom behind it, and the score")
    public LoanDtos.EligibilityResponse eligibility() {
        return loans.eligibility(currentUser.requireId());
    }

    /**
     * The credit score, with the breakdown behind it.
     *
     * <p>This is <b>Kudi9ja's own view</b>, built only from what the customer
     * has done with us. It is not a credit-bureau score, the Privacy Policy
     * says so, and the same document gives the customer the right to demand a
     * human review of any automated decision that goes against them — which is
     * why the factors are itemised here rather than reduced to a number.
     */
    @GetMapping("/credit-score")
    @Operation(summary = "The score out of 850, and what each part of it is worth")
    public LoanDtos.CreditScoreResponse creditScore() {
        return creditScores.explain(currentUser.requireId());
    }

    @GetMapping("/loans")
    @Operation(summary = "Every loan on this account")
    public List<LoanDtos.LoanResponse> list() {
        return loans.list(currentUser.requireId()).stream()
                .map(loans::toResponse)
                .toList();
    }

    @GetMapping("/loans/{loanId}")
    @Operation(summary = "One loan, with its schedule and settlement figures")
    public LoanDtos.LoanResponse one(@PathVariable UUID loanId) {
        return loans.toResponse(loans.get(currentUser.requireId(), loanId));
    }

    // Requesting a loan is no longer here. It was a POST that lent money in the
    // time it took to answer, on arithmetic over our own records — which could
    // never tell whether the business being lent against existed. It is now an
    // application with evidence attached, decided by a person: see
    // LoanApplicationController.

    @PostMapping("/loans/{loanId}/repay")
    @Operation(summary = "Repay from the wallet")
    public LoanDtos.RepaymentResponse repay(
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanDtos.RepayRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        UUID userId = currentUser.requireId();
        return idempotency.execute(
                userId,
                key,
                "loan.repay",
                new Object[] {loanId, request.amount()},
                LoanDtos.RepaymentResponse.class,
                () -> loans.repay(userId, loanId, request.amount(), request.pin()))
                .value();
    }

    /**
     * Settles early, with the rebate.
     *
     * <p>Half the interest attributable to the months that never started comes
     * back, capped at the outstanding balance. There is no early-settlement
     * charge — paying a debt off sooner should cost less, not more.
     */
    @PostMapping("/loans/{loanId}/settle")
    @Operation(summary = "Settle early. Part of the unearned interest is rebated.")
    public LoanDtos.RepaymentResponse settle(
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanDtos.LoanPinRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        UUID userId = currentUser.requireId();
        return idempotency.execute(
                userId,
                key,
                "loan.settle",
                loanId,
                LoanDtos.RepaymentResponse.class,
                () -> loans.settleEarly(userId, loanId, request.pin()))
                .value();
    }

    /**
     * The change-of-mind cancellation the Lending Agreement grants.
     *
     * <p>Inside the window, return what was received plus the fee, pay <b>no
     * interest</b>, and the loan leaves the record as a cancelled row. The
     * agreement has always promised this; the Flutter client never built it,
     * which left a written commitment the product could not honour.
     */
    @PostMapping("/loans/{loanId}/cancel")
    @Operation(summary = "Cancel inside the change-of-mind window. No interest is charged.")
    public LoanDtos.RepaymentResponse cancel(
            @PathVariable UUID loanId,
            @Valid @RequestBody LoanDtos.LoanPinRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {

        UUID userId = currentUser.requireId();
        return idempotency.execute(
                userId,
                key,
                "loan.cancel",
                loanId,
                LoanDtos.RepaymentResponse.class,
                () -> loans.cancel(userId, loanId, request.pin()))
                .value();
    }
}
