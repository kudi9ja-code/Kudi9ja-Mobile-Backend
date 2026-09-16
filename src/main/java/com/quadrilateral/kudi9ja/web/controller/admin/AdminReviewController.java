package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.review.ReviewService;
import com.quadrilateral.kudi9ja.web.dto.ReviewDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one thing an admin may do to a review: remove it, with a reason, on
 * the record. There is no endpoint for editing one or for hiding it from the
 * average — a review is the customer's words or it is gone.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews")
@Tag(name = "Admin — reviews", description = "Removing an abusive review, on the record")
public class AdminReviewController {

    private final ReviewService reviews;

    public AdminReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @DeleteMapping("/{reviewId}")
    @Operation(summary = "Remove a review, saying why")
    public Map<String, Boolean> remove(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewDtos.RemoveReviewRequest request) {
        reviews.remove(reviewId, request.reason());
        return Map.of("removed", true);
    }
}
