package com.quadrilateral.kudi9ja.domain.review;

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
 * A customer's rating of the app, with what they said about it.
 *
 * <p>One per customer, and theirs to change: a review is an opinion held now,
 * not a record of one held once, so a second rating replaces the first rather
 * than sitting beside it. Every customer can read every review — that is the
 * point of asking.
 *
 * <p>The name shown is the customer's first name and last initial, copied
 * here when they write. Their full name is on their account and stays there;
 * a screen every customer can read is not the place for it.
 */
@Entity
@Table(
        name = "app_review",
        indexes = {
                @Index(name = "ux_app_review_user", columnList = "user_id", unique = true),
                @Index(name = "ix_app_review_written", columnList = "updated_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class AppReview {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** "Chioma A." — enough to be a person, not enough to be found. */
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /** One to five. */
    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "comment", nullable = false, length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** When it was last written; the order the list is shown in. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public static AppReview of(UUID userId, String displayName, int rating, String comment) {
        AppReview review = new AppReview();
        review.id = UUID.randomUUID();
        review.userId = userId;
        review.displayName = displayName;
        review.rating = rating;
        review.comment = comment;
        return review;
    }
}
