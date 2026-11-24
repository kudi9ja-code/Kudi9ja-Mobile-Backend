package com.quadrilateral.kudi9ja.domain.liveness;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.user.SignupDraftRepository;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three face-check photos taken at sign-up: blink, head turn, open mouth.
 *
 * <p>The movements are checked on the customer's device; the server keeps one
 * JPEG of each so the team can see who opened the account. It is not identity
 * verification and nothing is compared against the BVN photo.
 */
@Service
public class LivenessService {

    private static final Logger log = LoggerFactory.getLogger(LivenessService.class);
    private static final String PREFIX = "data:image/jpeg;base64,";
    private static final int MIN_BYTES = 100;
    private static final int MAX_BYTES = 1_500_000;
    private static final String RETRY = "The face check photos did not come through. Please do the face check again.";

    private final LivenessPhotoRepository photos;
    private final SignupDraftRepository drafts;
    private final ReceiptStorage storage;
    private final Kudi9jaProperties properties;

    public LivenessService(
            LivenessPhotoRepository photos,
            SignupDraftRepository drafts,
            ReceiptStorage storage,
            Kudi9jaProperties properties) {
        this.photos = photos;
        this.drafts = drafts;
        this.storage = storage;
        this.properties = properties;
    }

    /** Decodes and checks all three before anything is stored. */
    public Map<LivenessPhoto.Pose, byte[]> validate(String blink, String turn, String mouth) {
        Map<LivenessPhoto.Pose, byte[]> decoded = new EnumMap<>(LivenessPhoto.Pose.class);
        decoded.put(LivenessPhoto.Pose.BLINK, jpeg(blink));
        decoded.put(LivenessPhoto.Pose.TURN, jpeg(turn));
        decoded.put(LivenessPhoto.Pose.MOUTH, jpeg(mouth));
        return decoded;
    }

    /** Stores the photos for a sign-up, replacing any taken on an earlier try. */
    @Transactional
    public void storeForDraft(UUID draftId, Map<LivenessPhoto.Pose, byte[]> images) {
        discard(photos.findByDraftId(draftId));
        Instant now = Instant.now();
        images.forEach((pose, bytes) -> {
            String key = storage.store("signup-" + draftId, "face-" + pose.name().toLowerCase() + ".jpg",
                    "image/jpeg", bytes);
            LivenessPhoto photo = new LivenessPhoto();
            photo.setId(UUID.randomUUID());
            photo.setDraftId(draftId);
            photo.setPose(pose);
            photo.setStorageKey(key);
            photo.setContentType("image/jpeg");
            photo.setSizeBytes(bytes.length);
            photo.setCapturedAt(now);
            photos.save(photo);
        });
    }

    /** Whether this sign-up has its three photos. */
    @Transactional(readOnly = true)
    public boolean hasPhotos(UUID draftId) {
        return photos.findByDraftId(draftId).size() >= LivenessPhoto.Pose.values().length;
    }

    /** Hands a finished sign-up's photos to the new account. Inside the account-opening transaction. */
    @Transactional
    public void claimFor(UUID draftId, UUID userId) {
        for (LivenessPhoto photo : photos.findByDraftId(draftId)) {
            photo.setUserId(userId);
            photos.save(photo);
        }
    }

    @Transactional(readOnly = true)
    public List<LivenessPhoto> forUser(UUID userId) {
        return photos.findByUserIdOrderByCapturedAtDesc(userId);
    }

    /** A signed, expiring link for the admin panel. */
    public String signedUrl(LivenessPhoto photo) {
        Duration ttl = properties.storage().signedUrlTtl();
        return storage.signedUrl(photo.getStorageKey(), ttl);
    }

    /** Housekeeping: photos of sign-ups that were abandoned and whose draft is gone. */
    @Transactional
    public int purgeAbandoned(Instant before) {
        List<LivenessPhoto> stale = photos.findByUserIdIsNullAndCapturedAtBefore(before).stream()
                .filter(p -> !drafts.existsById(p.getDraftId()))
                .toList();
        discard(stale);
        return stale.size();
    }

    private void discard(List<LivenessPhoto> old) {
        for (LivenessPhoto photo : old) {
            try {
                storage.delete(photo.getStorageKey());
            } catch (RuntimeException e) {
                log.warn("Could not delete face-check photo {}: {}", photo.getStorageKey(), e.getMessage());
            }
            photos.delete(photo);
        }
    }

    private static byte[] jpeg(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith(PREFIX)) {
            throw ApiException.validation(RETRY);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(RETRY);
        }
        boolean isJpeg = bytes.length >= MIN_BYTES
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
        if (!isJpeg || bytes.length > MAX_BYTES) {
            throw ApiException.validation(RETRY);
        }
        return bytes;
    }
}
