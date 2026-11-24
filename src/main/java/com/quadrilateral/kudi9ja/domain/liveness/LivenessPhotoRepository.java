package com.quadrilateral.kudi9ja.domain.liveness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LivenessPhotoRepository extends JpaRepository<LivenessPhoto, UUID> {

    List<LivenessPhoto> findByDraftId(UUID draftId);

    List<LivenessPhoto> findByUserIdOrderByCapturedAtDesc(UUID userId);

    /** Photos of sign-ups that were never finished, older than {@code before}. */
    List<LivenessPhoto> findByUserIdIsNullAndCapturedAtBefore(Instant before);
}
