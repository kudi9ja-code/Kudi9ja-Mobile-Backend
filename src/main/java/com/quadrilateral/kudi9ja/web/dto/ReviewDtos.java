package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.review.AppReview;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Ratings and reviews of the app, as every customer reads them. */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    /**
     * One review as the list shows it.
     *
     * @param mine whether the reader wrote it — the one the app lets them
     *             edit or withdraw
     */
    public record ReviewResponse(
            UUID id,
            String displayName,
            int rating,
            String comment,
            Instant writtenAt,
            boolean edited,
            boolean mine) {

        public static ReviewResponse from(AppReview review, UUID reader) {
            return new ReviewResponse(
                    review.getId(),
                    review.getDisplayName(),
                    review.getRating(),
                    review.getComment(),
                    review.getUpdatedAt(),
                    review.getUpdatedAt().isAfter(review.getCreatedAt()),
                    review.getUserId().equals(reader));
        }
    }

    /**
     * The headline: the average to one decimal, or null until anybody has
     * rated; the count; and how many gave each star.
     */
    public record Summary(
            Double average,
            long count,
            long fiveStar,
            long fourStar,
            long threeStar,
            long twoStar,
            long oneStar) {
    }

    public record SubmitReviewRequest(
            @NotNull(message = "Pick between one and five stars.")
            @Min(value = 1, message = "Pick between one and five stars.")
            @Max(value = 5, message = "Pick between one and five stars.")
            Integer rating,

            @NotBlank(message = "Say a few words about the app alongside the stars.")
            @Size(max = 500, message = "Keep your review under 500 characters.")
            String comment) {
    }

    /** An admin removing a review, and saying why. */
    public record RemoveReviewRequest(
            @NotBlank(message = "Say why this review is being removed.")
            @Size(max = 500, message = "Keep the reason short.")
            String reason) {
    }
}
