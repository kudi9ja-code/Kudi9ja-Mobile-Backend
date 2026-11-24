package com.quadrilateral.kudi9ja.domain.credit;

import com.quadrilateral.kudi9ja.integration.credit.CreditBureau;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One credit bureau check of a customer, fetched by an admin. Admin panel only. */
@Entity
@Table(name = "credit_check")
@Getter
@NoArgsConstructor
public class CreditCheck {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    /** The admin's name, as it was when they checked. */
    @Column(name = "checked_by", nullable = false, length = 160)
    private String checkedBy;

    @Column(name = "found", nullable = false)
    private boolean found;

    @Column(name = "score")
    private Integer score;

    @Column(name = "rating", length = 60)
    private String rating;

    @Column(name = "reasons", columnDefinition = "text")
    private String reasons;

    @Column(name = "has_loans", length = 20)
    private String hasLoans;

    @Column(name = "delinquent_facilities")
    private Integer delinquentFacilities;

    @Column(name = "last_reported", length = 40)
    private String lastReported;

    public static CreditCheck of(UUID userId, String checkedBy, CreditBureau.Report report) {
        CreditCheck check = new CreditCheck();
        check.id = UUID.randomUUID();
        check.userId = userId;
        check.checkedAt = Instant.now();
        check.checkedBy = cut(checkedBy, 160);
        check.found = report.found();
        check.score = report.score();
        check.rating = cut(report.rating(), 60);
        check.reasons = report.reasons();
        check.hasLoans = cut(report.hasLoans(), 20);
        check.delinquentFacilities = report.delinquentFacilities();
        check.lastReported = cut(report.lastReported(), 40);
        return check;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
