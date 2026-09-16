package com.quadrilateral.kudi9ja.domain.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppReviewRepository extends JpaRepository<AppReview, UUID> {

    Optional<AppReview> findByUserId(UUID userId);

    Page<AppReview> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /** How many gave each star, one row per rating that anybody gave. */
    @Query("select r.rating, count(r) from AppReview r group by r.rating")
    List<Object[]> countByRating();

    int deleteByUserId(UUID userId);
}
