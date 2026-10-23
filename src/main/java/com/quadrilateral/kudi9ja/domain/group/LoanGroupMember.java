package com.quadrilateral.kudi9ja.domain.group;

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
 * One borrower in a group: a customer with an account, a paper loan whose
 * borrower has not signed up, or — once they sign up — both.
 */
@Entity
@Table(
        name = "loan_group_member",
        indexes = {
                @Index(name = "ix_loan_group_member_group", columnList = "group_id"),
                @Index(name = "ix_loan_group_member_user", columnList = "user_id"),
                @Index(name = "ix_loan_group_member_import", columnList = "imported_loan_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanGroupMember {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "imported_loan_id")
    private UUID importedLoanId;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt = Instant.now();

    @Column(name = "added_by", nullable = false, length = 200, updatable = false)
    private String addedBy;
}
