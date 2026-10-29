package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.payout.WithdrawalRequest;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalStatus;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.domain.wallet.TxStatus;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The wallet, its ledger, and the two ways money leaves it. */
public final class WalletDtos {

    private WalletDtos() {
    }

    /**
     * The wallet.
     *
     * <p>It is <b>not a bank account</b> and is <b>not NDIC-insured</b>. The
     * disclosure travels with the balance so no screen can show one without the
     * other.
     */
    public record WalletResponse(
            BigDecimal balance,
            String customerRef,
            BigDecimal totalSaved,
            BigDecimal totalOwed,
            BigDecimal netWorth,
            BigDecimal totalInterestEarned,
            BigDecimal totalDeposited,
            BigDecimal pendingWithdrawals,
            boolean hideBalance,
            String disclosure) {
    }

    public record TransactionResponse(
            UUID id,
            long sequence,
            TxKind kind,
            String kindLabel,
            boolean credit,
            BigDecimal amount,
            String description,
            Instant occurredAt,
            BigDecimal balanceAfter,
            String reference,
            String counterparty,
            TxStatus status,
            String statusLabel,
            String relatedType,
            UUID relatedId) {

        public static TransactionResponse from(WalletTransaction tx) {
            return new TransactionResponse(
                    tx.getId(),
                    tx.getSequence(),
                    tx.getKind(),
                    tx.getKind().label(),
                    tx.isCredit(),
                    tx.getAmount(),
                    tx.getDescription(),
                    tx.getOccurredAt(),
                    tx.getBalanceAfter(),
                    tx.getReference(),
                    tx.getCounterparty(),
                    tx.getStatus(),
                    tx.getStatus().label(),
                    tx.getRelatedType(),
                    tx.getRelatedId());
        }
    }

    /**
     * Asking for money out.
     *
     * @param bank          optionally, where the app has told the customer the
     *                      money is going
     * @param accountNumber and the account it named
     *
     *     <p>Neither chooses the destination — that is always the payout
     *     account on the customer's own record, resolved and name-matched when
     *     it was set. They are checked against it and the request is refused if
     *     they differ, so an app that showed the customer one destination
     *     cannot quietly send the money to another. Omit them and the payout
     *     account is used without comment.
     */
    public record WithdrawRequest(
            @NotNull(message = "How much would you like to withdraw?")
            @DecimalMin(value = "0.01", message = "An amount must be above zero.")
            BigDecimal amount,

            @NotBlank(message = "Your PIN is needed.")
            String pin,

            String bank,

            String accountNumber) {
    }

    public record WithdrawalResponse(
            UUID id,
            BigDecimal amount,
            String bank,
            String destinationAccount,
            String destinationName,
            String reference,
            WithdrawalStatus status,
            String statusLabel,
            Instant requestedAt,
            Instant reviewedAt,
            String reviewedBy,
            String note,
            BigDecimal newBalance,
            String message,
            /** Where the money is: PROCESSING, PAID or FAILED (refunded). Null until approved. */
            String payoutStatus,
            String payoutFailure,
            Instant paidOutAt) {

        public static WithdrawalResponse from(WithdrawalRequest request, BigDecimal balance, String message) {
            return new WithdrawalResponse(
                    request.getId(),
                    request.getAmount(),
                    request.getBank(),
                    com.quadrilateral.kudi9ja.common.util.Masks.accountTail(request.getDestinationAccount()),
                    request.getDestinationName(),
                    request.getReference(),
                    request.getStatus(),
                    request.getStatus().label(),
                    request.getRequestedAt(),
                    request.getReviewedAt(),
                    request.getReviewedBy(),
                    request.getNote(),
                    balance,
                    message,
                    request.getPayoutStatus(),
                    request.getPayoutFailure(),
                    request.getPaidOutAt());
        }
    }

    /** The admin's view, with the full destination and how long it has waited. */
    public record AdminWithdrawalResponse(
            UUID id,
            UUID userId,
            String customerName,
            String customerRef,
            BigDecimal amount,
            String bank,
            String destinationAccount,
            String destinationName,
            String reference,
            WithdrawalStatus status,
            String statusLabel,
            Instant requestedAt,
            long hoursWaiting,
            Instant reviewedAt,
            String reviewedBy,
            String note,
            String payoutReference,
            String payoutProvider,
            String payoutStatus,
            String transferCode,
            String payoutFailure,
            Instant paidOutAt) {
    }

    /**
     * {@code paidByHand}: the money was already sent from the company account,
     * so approval only records it and Paystack is not called.
     */
    public record ApproveWithdrawalRequest(String payoutReference, String note, Boolean paidByHand) {
    }

    public record DeclineWithdrawalRequest(
            @NotBlank(message = "Give a reason. The customer is told it.")
            String reason) {
    }

    /** Whether a wallet still matches its ledger. */
    public record ReconciliationResponse(
            UUID userId,
            BigDecimal storedBalance,
            BigDecimal ledgerBalance,
            BigDecimal difference,
            boolean balanced) {
    }
}
