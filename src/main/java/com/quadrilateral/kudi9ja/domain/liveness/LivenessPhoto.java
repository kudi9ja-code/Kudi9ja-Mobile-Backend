package com.quadrilateral.kudi9ja.domain.liveness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One of the three photos taken during the face check at sign-up.
 *
 * <p>Belongs to the sign-up draft until the account opens, then to the
 * customer. Shown on the customer's record in the admin panel. Not identity
 * verification.
 */
@Entity
@Table(name = "liveness_photo")
@Getter
@Setter
@NoArgsConstructor
public class LivenessPhoto {

    /** What the customer was asked to do when the photo was taken. */
    public enum Pose {
        BLINK,
        TURN,
        MOUTH
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pose", nullable = false, length = 16)
    private Pose pose;

    @Column(name = "storage_key", nullable = false, length = 400)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 60)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
}
