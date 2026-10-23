package com.quadrilateral.kudi9ja.domain.officer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One member of the team looking after one borrower.
 *
 * <p>Exactly one of {@code userId} and {@code importedLoanId} is set: a
 * customer with an account, or a paper loan whose borrower has not signed up
 * yet. When they sign up, the assignment moves from the second to the first.
 */
@Entity
@Table(
        name = "loan_officer_assignment",
        indexes = {
                @Index(name = "ix_loan_officer_officer", columnList = "officer_id"),
                @Index(name = "ux_loan_officer_user", columnList = "user_id, officer_id", unique = true),
                @Index(name = "ux_loan_officer_import", columnList = "imported_loan_id, officer_id", unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanOfficerAssignment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "imported_loan_id")
    private UUID importedLoanId;

    /** The officer's panel grant. */
    @Column(name = "officer_id", nullable = false, updatable = false)
    private UUID officerId;

    /** The officer's own Kudi9ja account. */
    @Column(name = "officer_user_id", nullable = false, updatable = false)
    private UUID officerUserId;

    @Column(name = "officer_name", nullable = false, length = 160)
    private String officerName;

    @Column(name = "officer_email", nullable = false, length = 254)
    private String officerEmail;

    /** The first person to call. One per borrower; the rest support. */
    @Column(name = "is_lead", nullable = false)
    private boolean lead;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "assigned_by", nullable = false, length = 200, updatable = false)
    private String assignedBy;
}
