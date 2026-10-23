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
 * A group loan: a numbered group of borrowers from one place — "Group 3,
 * Oja Oba market" — looked after by one loan officer.
 *
 * <p>The officer's name and email are copied here so the group still says who
 * ran it after their panel access ends.
 */
@Entity
@Table(name = "loan_group", indexes = @Index(name = "ux_loan_group_number", columnList = "number", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class LoanGroup {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Group 1, Group 2, … what the team calls it. */
    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Where its borrowers are: a market, a town, a cooperative. */
    @Column(name = "area", length = 200)
    private String area;

    @Column(name = "description", length = 1000)
    private String description;

    /** The officer's panel grant. */
    @Column(name = "officer_id", nullable = false)
    private UUID officerId;

    @Column(name = "officer_user_id", nullable = false)
    private UUID officerUserId;

    @Column(name = "officer_name", nullable = false, length = 160)
    private String officerName;

    @Column(name = "officer_email", nullable = false, length = 254)
    private String officerEmail;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, length = 200, updatable = false)
    private String createdBy;

    public String label() {
        return "Group " + number + " — " + name;
    }
}
