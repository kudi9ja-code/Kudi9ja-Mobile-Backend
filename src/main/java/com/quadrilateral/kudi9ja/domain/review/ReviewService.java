package com.quadrilateral.kudi9ja.domain.review;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.web.dto.ReviewDtos;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What customers think of the app, said where every other customer can read it.
 *
 * <p>Three rules. A customer has one review and may rewrite or withdraw it.
 * Every signed-in customer sees every review, and the average, exactly as
 * written — nothing is curated into a better-looking number. An admin may
 * remove one, and that is recorded, because the honest reading of an admin
 * deleting a one-star review is the obvious one.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    static final int MIN_RATING = 1;
    static final int MAX_RATING = 5;
    static final int MIN_COMMENT = 3;
    static final int MAX_COMMENT = 500;

    private final AppReviewRepository reviews;
    private final UserRepository users;
    private final AdminAccessService access;
    private final AuditService audit;

    public ReviewService(
            AppReviewRepository reviews,
            UserRepository users,
            AdminAccessService access,
            AuditService audit) {
        this.reviews = reviews;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Every review, most recently written first. */
    @Transactional(readOnly = true)
    public Page<AppReview> list(Pageable pageable) {
        return reviews.findAllByOrderByUpdatedAtDesc(pageable);
    }

    /** The average, the count, and how many gave each star. */
    @Transactional(readOnly = true)
    public ReviewDtos.Summary summary() {
        long[] perStar = new long[MAX_RATING + 1];
        long total = 0;
        long sum = 0;
        for (Object[] row : reviews.countByRating()) {
            int rating = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            if (rating >= MIN_RATING && rating <= MAX_RATING) {
                perStar[rating] = count;
                total += count;
                sum += (long) rating * count;
            }
        }
        // One decimal, the way a store shows it. Zero reviews is "no rating",
        // not "0.0 stars", so the average is null until somebody has spoken.
        Double average = total == 0 ? null : Math.round(10.0 * sum / total) / 10.0;
        return new ReviewDtos.Summary(
                average,
                total,
                perStar[5], perStar[4], perStar[3], perStar[2], perStar[1]);
    }

    @Transactional(readOnly = true)
    public Optional<AppReview> mine(UUID userId) {
        return reviews.findByUserId(userId);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Writes or rewrites this customer's review.
     *
     * <p>The name shown beside it is taken from the account now, so a customer
     * who changed their name through support is not still signed as the old
     * one.
     */
    @Transactional
    public AppReview submit(UUID userId, int rating, String comment) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw ApiException.validation("Pick between one and five stars.");
        }
        String written = comment == null ? "" : comment.trim();
        if (written.length() < MIN_COMMENT) {
            throw ApiException.validation("Say a few words about the app alongside the stars.");
        }
        if (written.length() > MAX_COMMENT) {
            throw ApiException.validation("Keep your review under " + MAX_COMMENT + " characters.");
        }

        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));
        String name = displayNameFor(user.getFullName());

        AppReview review = reviews.findByUserId(userId).orElse(null);
        boolean first = review == null;
        if (first) {
            review = AppReview.of(userId, name, rating, written);
        } else {
            review.setDisplayName(name);
            review.setRating(rating);
            review.setComment(written);
            review.setUpdatedAt(Instant.now());
        }
        AppReview saved = reviews.save(review);
        log.info("{} {} review: {} star(s)", user.getCustomerRef(), first ? "left a" : "rewrote their", rating);
        return saved;
    }

    /** Withdraws this customer's own review. Nothing to withdraw is not an error. */
    @Transactional
    public boolean withdraw(UUID userId) {
        return reviews.deleteByUserId(userId) > 0;
    }

    /**
     * Removes any review, by an admin, on the record.
     *
     * <p>For abuse — a phone number, an insult, somebody else's name — and
     * audited every time, because the other reason an admin might reach for
     * this is that the review was unflattering, and that is the reason the
     * audit log exists.
     */
    @Transactional
    public void remove(UUID reviewId, String reason) {
        AdminUser actor = access.requireCanManageCustomers();
        AppReview review = reviews.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("That review"));
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 5) {
            throw ApiException.validation("Say why this review is being removed.");
        }
        reviews.delete(review);
        audit.record(
                new AuditService.Actor(actor.getUserId(), actor.getName(), actor.getEmail()),
                AuditCategory.CUSTOMER,
                "App review removed",
                actor.getName() + " removed a " + review.getRating() + "-star review by "
                        + review.getDisplayName() + ". Reason: " + why
                        + " The review read: \"" + review.getComment() + "\"",
                review.getUserId(),
                null);
    }

    /**
     * "Chioma Grace Adeyemi" becomes "Chioma A." — a person, not a lookup key.
     * A single name stays as it is.
     */
    public static String displayNameFor(String fullName) {
        String[] parts = (fullName == null ? "" : fullName.trim()).split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "A customer";
        }
        String first = parts[0];
        if (parts.length == 1) {
            return first;
        }
        String last = parts[parts.length - 1];
        return first + " " + last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }
}
