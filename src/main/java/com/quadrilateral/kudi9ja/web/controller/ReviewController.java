package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.review.AppReview;
import com.quadrilateral.kudi9ja.domain.review.ReviewService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.ReviewDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ratings and reviews of the app.
 *
 * <p>Any signed-in customer may read every review and the average; any may
 * write one, rewrite it, or take it back. Nothing here is curated: what the
 * list shows is what customers wrote, newest first.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "What customers think of the app, where every customer can read it")
public class ReviewController {

    private final ReviewService reviews;
    private final CurrentUser currentUser;

    public ReviewController(ReviewService reviews, CurrentUser currentUser) {
        this.reviews = reviews;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Every review, most recently written first")
    public PageResponse<ReviewDtos.ReviewResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID reader = currentUser.requireId();
        return PageResponse.of(
                reviews.list(PageRequest.of(page, Math.min(size, 100))),
                review -> ReviewDtos.ReviewResponse.from(review, reader));
    }

    @GetMapping("/summary")
    @Operation(summary = "The average rating, the count, and how many gave each star")
    public ReviewDtos.Summary summary() {
        currentUser.requireId();
        return reviews.summary();
    }

    @GetMapping("/mine")
    @Operation(summary = "This customer's own review, if they have written one")
    public ResponseEntity<ReviewDtos.ReviewResponse> mine() {
        UUID reader = currentUser.requireId();
        return reviews.mine(reader)
                .map(review -> ResponseEntity.ok(ReviewDtos.ReviewResponse.from(review, reader)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/mine")
    @Operation(summary = "Write, or rewrite, this customer's review")
    public ReviewDtos.ReviewResponse submit(@Valid @RequestBody ReviewDtos.SubmitReviewRequest request) {
        UUID reader = currentUser.requireId();
        AppReview saved = reviews.submit(reader, request.rating(), request.comment());
        return ReviewDtos.ReviewResponse.from(saved, reader);
    }

    @DeleteMapping("/mine")
    @Operation(summary = "Withdraw this customer's review")
    public Map<String, Boolean> withdraw() {
        return Map.of("removed", reviews.withdraw(currentUser.requireId()));
    }
}
