package com.quadrilateral.kudi9ja.domain.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A repayment that arrived outside the app, entered by an admin.
 *
 * <p>Cash handed over, a transfer straight to the company account without a
 * claim — money that never passed through a wallet, so it never touches the
 * ledger. The loan's amount repaid moves; the row here is the record of why,
 * by whom, and with what reference, so the figure can always be explained.
 */
@Entity
@Table(
        name = "recorded_repayment",
        indexes = {
                @Index(name = "ix_recorded_repayment_import", columnList = "imported_loan_id, paid_at"),
                @Index(name = "ix_recorded_repayment_loan", columnList = "loan_id, paid_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class RecordedRepayment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "imported_loan_id", updatable = false)
    private UUID importedLoanId;

    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    /** How it was paid: CASH, BANK_TRANSFER, POS, CHEQUE, OTHER. */
    @Column(name = "method", nullable = false, length = 40, updatable = false)
    private String method;

    @Column(name = "reference", length = 120, updatable = false)
    private String reference;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "recorded_by", nullable = false, length = 200, updatable = false)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
