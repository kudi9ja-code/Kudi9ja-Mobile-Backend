package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * <p>The BVN, NIN, date of birth, email and phone are cleared when the row is
 * claimed. The account holds them from then on, and a second copy is one more
 * place identity data can leak from. The rest of the file — address, business,
 * guarantors, documents — belongs to the loan and stays with it.
 */
@Entity
@Table(
        name = "imported_loan",
        indexes = {
                @Index(name = "ix_imported_loan_bvn", columnList = "bvn"),
                @Index(name = "ix_imported_loan_claimed", columnList = "claimed_at, imported_at"),
                @Index(name = "ix_imported_loan_user", columnList = "user_id"),
                @Index(name = "ix_imported_loan_loan", columnList = "loan_id")
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

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "repayment_frequency", nullable = false, length = 16, updatable = false)
    private RepaymentFrequency repaymentFrequency = RepaymentFrequency.MONTHLY;

    /** The rate that was actually charged, not the rate card's. */
    @Column(name = "flat_rate", nullable = false, precision = 12, scale = 6, updatable = false)
    private BigDecimal flatRate;

    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal processingFee;

    /** The transaction fee taken when the loan was given, on its own line. Zero on older rows. */
    @Column(name = "transaction_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal transactionFee = BigDecimal.ZERO;

    /** The transaction fee on each repayment, as a fraction of the instalment. */
    @Column(name = "repayment_fee_rate", nullable = false, precision = 12, scale = 6, updatable = false)
    private BigDecimal repaymentFeeRate = BigDecimal.ZERO;

    @Column(name = "purpose", nullable = false, length = 200)
    private String purpose;

    @Column(name = "disbursed_at", nullable = false, updatable = false)
    private Instant disbursedAt;

    // Updatable: an admin records repayments that arrive outside the app
    // while the loan is still waiting for its borrower.
    @Column(name = "amount_repaid", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRepaid;

    // The borrower's file -------------------------------------------------------
    //
    // Everything an in-app application would have told us, entered by the
    // admin from the paper records. All optional: the debt is the debt whether
    // or not the notebook recorded a landmark.

    @Column(name = "nin", length = 11)
    private String nin;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 40)
    private String gender;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "state", length = 60)
    private String state;

    @Column(name = "occupation", length = 160)
    private String occupation;

    @Column(name = "business_name", length = 200)
    private String businessName;

    @Column(name = "business_address", length = 400)
    private String businessAddress;

    @Column(name = "monthly_income", precision = 19, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "payout_bank", length = 120)
    private String payoutBank;

    @Column(name = "payout_account_number", length = 10)
    private String payoutAccountNumber;

    @Column(name = "notes", length = 2000)
    private String notes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "imported_loan_guarantor",
            joinColumns = @JoinColumn(name = "imported_loan_id", nullable = false),
            indexes = @Index(name = "ix_imported_loan_guarantor", columnList = "imported_loan_id"))
    @OrderColumn(name = "position")
    private List<OfflineGuarantor> guarantors = new ArrayList<>();

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

    /** The loan, its interest and the transaction fee on every repayment, priced as on its account. */
    public BigDecimal totalRepayable() {
        return terms().totalRepayable();
    }

    public BigDecimal repaymentFees() {
        return terms().repaymentFees();
    }

    public int installmentCount() {
        return terms().installmentCount();
    }

    /** The loan on the terms it was entered with, before it is on an account. */
    private Loan terms() {
        Loan loan = new Loan();
        loan.setPrincipal(principal);
        loan.setTenureMonths(tenureMonths);
        loan.setRepaymentFrequency(RepaymentFrequency.orMonthly(repaymentFrequency));
        loan.setFlatRate(flatRate);
        loan.setProcessingFee(processingFee);
        loan.setTransactionFee(transactionFee());
        loan.setDisbursedAt(disbursedAt);
        loan.setAmountRepaid(amountRepaid);
        loan.setRebateGranted(Money.zero());
        loan.fixRepaymentTerms(disbursedAt, repaymentFeeRate);
        return loan;
    }


    public Instant dueDate() {
        return disbursedAt == null ? null : terms().getDueDate();
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
        loan.setRepaymentFrequency(RepaymentFrequency.orMonthly(repaymentFrequency));
        loan.setFlatRate(flatRate);
        loan.setProcessingFee(processingFee);
        loan.setTransactionFee(transactionFee());
        loan.setPurpose(purpose);
        loan.setRequestedAt(disbursedAt);
        loan.setDisbursedAt(disbursedAt);
        loan.setAmountRepaid(amountRepaid);
        loan.setRebateGranted(Money.zero());
        // The count, the fee on each repayment and the due date.
        loan.fixRepaymentTerms(disbursedAt, repaymentFeeRate);
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

    public BigDecimal transactionFee() {
        return transactionFee == null ? Money.zero() : transactionFee;
    }

    /** What the borrower actually received: the loan, less both fees. */
    public BigDecimal netDisbursed() {
        return Money.subtract(Money.subtract(principal, processingFee), transactionFee());
    }

    /** Records the claim and drops the identity data the account now holds. */
    public void claimedBy(UUID userId, UUID loanId, Instant now) {
        this.claimedAt = now;
        this.userId = userId;
        this.loanId = loanId;
        this.bvn = null;
        this.email = null;
        this.phone = null;
        this.nin = null;
        this.dateOfBirth = null;
    }
}
