package com.quadrilateral.kudi9ja.domain.loanapplication;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request to borrow, with everything a person needs in order to decide it.
 *
 * <p><b>No money moves on this row.</b> Borrowing used to be settled by the
 * server the moment it was asked for: affordability computed, the offer
 * checked, and the wallet credited in the same request. That arithmetic still
 * runs, but the arithmetic was never the whole decision — whether the business
 * exists, whether the statement shows what the applicant says it shows, whether
 * two named people will really stand behind it. Those are questions for a
 * person, so the application waits until one has answered them.
 *
 * <p>What it carries beyond the figures:
 *
 * <ul>
 *   <li><b>A bank statement.</b> The one document that says what actually goes
 *       through the applicant's hands, as against what they report.
 *   <li><b>Three photographs of the business.</b> Cheap to ask for, hard to
 *       fake convincingly, and the quickest way to tell a going concern from a
 *       description of one.
 *   <li><b>Two guarantors.</b> Recorded in full, verified nowhere — see
 *       {@link Guarantor}.
 * </ul>
 *
 * <p>A rejection carries a reason and the customer is shown it. A refusal
 * somebody can act on is worth more than one they can only resent, and applying
 * again after fixing what was wrong is expected rather than grudgingly allowed.
 *
 * <p>The documents are kept after a decision, approval or refusal alike. They
 * are the evidence the decision was made on, and a lender that cannot say why
 * it lent — or why it did not — has no answer for a regulator or for the
 * customer.
 */
@Entity
@Table(
        name = "loan_application",
        indexes = {
                @Index(name = "ix_loanapp_user", columnList = "user_id, submitted_at"),
                @Index(name = "ix_loanapp_status", columnList = "status, submitted_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanApplication {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Copied at submission so the queue reads without a join, as elsewhere. */
    @Column(name = "customer_name", nullable = false, length = 160, updatable = false)
    private String customerName;

    @Column(name = "customer_ref", nullable = false, length = 16, updatable = false)
    private String customerRef;

    // -- What is being asked for --------------------------------------------

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "tenure_months", nullable = false, updatable = false)
    private int tenureMonths;

    @Column(name = "purpose", nullable = false, length = 200, updatable = false)
    private String purpose;

    // -- The business the money is for --------------------------------------

    @Column(name = "business_name", nullable = false, length = 200, updatable = false)
    private String businessName;

    @Column(name = "business_address", nullable = false, length = 400, updatable = false)
    private String businessAddress;

    /**
     * What the applicant says they take in a month.
     *
     * <p>Their claim, not a finding — the bank statement is what it is checked
     * against, and the gap between the two is often the most informative thing
     * on the application.
     */
    @Column(name = "monthly_income", precision = 19, scale = 2, updatable = false)
    private BigDecimal monthlyIncome;

    // -- Evidence -----------------------------------------------------------

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "key", column = @Column(name = "statement_key",
                    nullable = false, length = 300, updatable = false)),
            @AttributeOverride(name = "contentType", column = @Column(name = "statement_content_type",
                    length = 120, updatable = false)),
            @AttributeOverride(name = "sizeBytes", column = @Column(name = "statement_size_bytes",
                    updatable = false))
    })
    private StoredDocument bankStatement;

    /**
     * Photographs of the business premises, in the order they were uploaded.
     *
     * <p>A collection rather than three columns: three is what is asked for
     * today, and a table does not have to be altered when that becomes two or
     * four.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "loan_application_photo",
            joinColumns = @JoinColumn(name = "application_id", nullable = false),
            indexes = @Index(name = "ix_loanapp_photo", columnList = "application_id"))
    @OrderColumn(name = "position")
    private List<StoredDocument> businessPhotos = new ArrayList<>();

    /**
     * The two people vouching for this borrower.
     *
     * <p>Eager, and deliberately: an application is never read without them —
     * they are half of what the admin is deciding on.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "loan_application_guarantor",
            joinColumns = @JoinColumn(name = "application_id", nullable = false),
            indexes = @Index(name = "ix_loanapp_guarantor", columnList = "application_id"))
    @OrderColumn(name = "position")
    private List<Guarantor> guarantors = new ArrayList<>();

    // -- The decision -------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LoanApplicationStatus status = LoanApplicationStatus.PENDING;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Which admin decided it, by name and email, as the audit log records. */
    @Column(name = "reviewed_by", length = 200)
    private String reviewedBy;

    /**
     * Why it was turned down, in words written for the customer who reads it.
     * Empty on an approval.
     */
    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    /** The loan this became, once approved. Null until then. */
    @Column(name = "loan_id")
    private UUID loanId;

    /**
     * What the automated assessment made of it at submission.
     *
     * <p>Kept because the admin decides <i>with</i> it rather than instead of
     * it, and because a refusal that disagreed with the score is worth being
     * able to find later.
     */
    @Column(name = "score_at_submission")
    private Integer scoreAtSubmission;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isPending() {
        return status == LoanApplicationStatus.PENDING;
    }

    /** Every uploaded document on this application, the statement first. */
    public List<StoredDocument> allDocuments() {
        List<StoredDocument> all = new ArrayList<>();
        if (bankStatement != null) {
            all.add(bankStatement);
        }
        all.addAll(businessPhotos);
        return all;
    }
}
