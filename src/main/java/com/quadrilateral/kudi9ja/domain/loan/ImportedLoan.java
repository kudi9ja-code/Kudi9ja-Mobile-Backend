package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
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
 * A loan written on paper before the customer had an account.
 *
 * <p>For about a month the company lent by hand — a transfer from the company
 * account, a schedule in a notebook — to customers who have not signed up yet.
 * An admin enters each of those loans here against the customer's BVN. When
 * that BVN is verified at signup, the row becomes an ordinary {@link Loan} on
 * the new account and the customer opens the app to find what they owe already
 * there.
 *
 * <p>Nothing here touches the ledger. The money moved outside the app, so the
 * wallet stays at zero and only the loan book records the debt.
 *
 * <p>The BVN, email and phone are cleared when the row is claimed. The account
 * holds them from then on, and a second copy is one more place identity data
 * can leak from.
 */
@Entity
@Table(
        name = "imported_loan",
        indexes = {
                @Index(name = "ix_imported_loan_bvn", columnList = "bvn"),
                @Index(name = "ix_imported_loan_claimed", columnList = "claimed_at, imported_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class ImportedLoan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Who ---------------------------------------------------------------------

    /** The match key. Null once claimed. */
    @Column(name = "bvn", length = 11)
    private String bvn;

    /** As it appears on the BVN, for the admin's eye. */
    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "email", length = 190)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    // The loan, as agreed on paper ----------------------------------------------

    @Column(name = "principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principal;

    @Column(name = "tenure_months", nullable = false, updatable = false)
    private int tenureMonths;

    /** The rate that was actually charged, not the rate card's. */
    @Column(name = "flat_rate", nullable = false, precision = 12, scale = 6, updatable = false)
    private BigDecimal flatRate;

    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal processingFee;

    @Column(name = "purpose", nullable = false, length = 200)
    private String purpose;

    @Column(name = "disbursed_at", nullable = false, updatable = false)
    private Instant disbursedAt;

    @Column(name = "amount_repaid", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amountRepaid;

    // Lifecycle -------------------------------------------------------------------

    @Column(name = "imported_by", nullable = false, length = 200)
    private String importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt = Instant.now();

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "loan_id")
    private UUID loanId;

    // Derived ---------------------------------------------------------------------

    public BigDecimal totalRepayable() {
        return Money.add(principal, Money.multiply(principal, flatRate));
    }

    public Instant dueDate() {
        return Dates.addMonths(disbursedAt, tenureMonths);
    }

    public BigDecimal outstanding() {
        return Money.floorAtZero(Money.subtract(totalRepayable(), amountRepaid));
    }

    public boolean isClaimed() {
        return claimedAt != null;
    }

    /**
     * Turns the paper record into a loan on the account.
     *
     * <p>Where it stands follows from the figures, not from what the admin
     * typed: cleared if nothing is left, overdue if the due date has passed,
     * active otherwise. A repaid loan's settlement date is unknown, so the
     * import date stands in for it.
     */
    public Loan toLoan(UUID userId, Instant now) {
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUserId(userId);
        loan.setPrincipal(principal);
        loan.setTenureMonths(tenureMonths);
        loan.setFlatRate(flatRate);
        loan.setProcessingFee(processingFee);
        loan.setPurpose(purpose);
        loan.setRequestedAt(disbursedAt);
        loan.setDisbursedAt(disbursedAt);
        loan.setDueDate(dueDate());
        loan.setAmountRepaid(amountRepaid);
        loan.setRebateGranted(Money.zero());
        loan.setDecidedBy("Imported from the paper records by " + importedBy);

        if (Money.isZeroOrLess(loan.outstanding())) {
            loan.setStatus(LoanStatus.REPAID);
            loan.setSettledAt(importedAt);
        } else if (now.isAfter(loan.getDueDate())) {
            loan.setStatus(LoanStatus.OVERDUE);
        } else {
            loan.setStatus(LoanStatus.ACTIVE);
        }
        return loan;
    }

    /** Records the claim and drops the identity data the account now holds. */
    public void claimedBy(UUID userId, UUID loanId, Instant now) {
        this.claimedAt = now;
        this.userId = userId;
        this.loanId = loanId;
        this.bvn = null;
        this.email = null;
        this.phone = null;
    }
}
