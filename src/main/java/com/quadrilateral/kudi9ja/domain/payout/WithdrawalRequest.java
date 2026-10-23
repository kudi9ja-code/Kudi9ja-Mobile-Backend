package com.quadrilateral.kudi9ja.domain.payout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request to move money out of the wallet, to the customer's own bank
 * account.
 *
 * <p><b>The id is the pending ledger transaction's id.</b> That is deliberate:
 * the wallet is debited when the request is made, not when it is approved, so
 * the same money cannot be spent twice while the request is in the queue.
 * Approving settles that transaction; declining reverses it and refunds in
 * full.
 *
 * <p>Money leaves only to an account in the customer's own name. The Terms
 * promise it, and the name enquiry at signup is what makes the promise good.
 */
@Entity
@Table(
        name = "withdrawal_request",
        indexes = {
                @Index(name = "ix_withdrawal_user", columnList = "user_id, requested_at"),
                @Index(name = "ix_withdrawal_status", columnList = "status, requested_at"),
                @Index(name = "ix_withdrawal_reference", columnList = "reference"),
                @Index(name = "ix_withdrawal_transfer_reference", columnList = "transfer_reference")
        })
@Getter
@Setter
@NoArgsConstructor
public class WithdrawalRequest {

    /** The same id as the pending ledger transaction this request debited. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "customer_name", nullable = false, length = 160, updatable = false)
    private String customerName;

    @Column(name = "customer_ref", nullable = false, length = 16, updatable = false)
    private String customerRef;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "bank", nullable = false, length = 120, updatable = false)
    private String bank;

    @Column(name = "destination_account", nullable = false, length = 10, updatable = false)
    private String destinationAccount;

    /** The name the bank holds on that account, as resolved at signup. */
    @Column(name = "destination_name", length = 160, updatable = false)
    private String destinationName;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt = Instant.now();

    /** Matches the ledger transaction. */
    @Column(name = "reference", nullable = false, length = 64, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by", length = 200)
    private String reviewedBy;

    /** The reason, when it was declined. The customer is told it. */
    @Column(name = "note", length = 1000)
    private String note;

    /** The bank's reference for the outward transfer, once it is sent. */
    @Column(name = "payout_reference", length = 120)
    private String payoutReference;

    // ── The payout ────────────────────────────────────────────────────────
    //
    // How the money actually left. MANUAL when an admin paid it by hand from
    // the company account; PAYSTACK when approval sent it as a transfer. A
    // Paystack payout is PROCESSING until Paystack reports the outcome: the
    // wallet debit stays pending until then, and a FAILED transfer is refunded.

    @Column(name = "payout_provider", length = 20)
    private String payoutProvider;

    @Column(name = "payout_status", length = 16)
    private String payoutStatus;

    @Column(name = "transfer_reference", length = 64)
    private String transferReference;

    @Column(name = "transfer_code", length = 64)
    private String transferCode;

    @Column(name = "payout_failure", length = 500)
    private String payoutFailure;

    @Column(name = "paid_out_at")
    private Instant paidOutAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isPending() {
        return status == WithdrawalStatus.PENDING;
    }
}
