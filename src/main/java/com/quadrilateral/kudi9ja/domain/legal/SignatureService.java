package com.quadrilateral.kudi9ja.domain.legal;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.integration.kyc.NameMatcher;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import com.quadrilateral.kudi9ja.web.dto.SignupDtos;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the signature a customer draws when they accept their agreements.
 *
 * <p>The website sends it as a PNG data URL with the name typed beside it.
 * Both are checked before the account exists: the image must really be a PNG
 * of a sensible size, and the typed name must be the name on the account —
 * a signature in somebody else's name is not this customer's signature.
 */
@Service
public class SignatureService {

    private static final String PREFIX = "data:image/png;base64,";
    private static final int MAX_BYTES = 400 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final AgreementSignatureRepository signatures;
    private final ReceiptStorage storage;
    private final Kudi9jaProperties properties;

    public SignatureService(
            AgreementSignatureRepository signatures, ReceiptStorage storage, Kudi9jaProperties properties) {
        this.signatures = signatures;
        this.storage = storage;
        this.properties = properties;
    }

    /** The decoded image, checked. Throws a validation error the customer can act on. */
    public byte[] validate(SignupDtos.SignatureInput input, String accountName) {
        if (input.image() == null || !input.image().startsWith(PREFIX)) {
            throw ApiException.validation("Draw your signature again — it did not come through.");
        }
        byte[] png;
        try {
            png = Base64.getDecoder().decode(input.image().substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Draw your signature again — it did not come through.");
        }
        if (png.length < PNG_MAGIC.length || png.length > MAX_BYTES) {
            throw ApiException.validation("Draw your signature again — it did not come through.");
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (png[i] != PNG_MAGIC[i]) {
                throw ApiException.validation("Draw your signature again — it did not come through.");
            }
        }
        if (input.typedName() == null || !NameMatcher.matches(input.typedName().trim(), accountName)) {
            throw ApiException.validation("Type your name exactly as it is on your account: " + accountName + ".");
        }
        return png;
    }

    /** Stores the signature. Called inside the transaction that opens the account. */
    @Transactional
    public AgreementSignature record(
            User user, SignupDtos.SignatureInput input, byte[] png,
            Map<LegalDocumentKind, String> versions, String device, String ipAddress) {

        String key = storage.store(user.getCustomerRef(), "signature.png", "image/png", png);

        AgreementSignature signature = new AgreementSignature();
        signature.setId(UUID.randomUUID());
        signature.setUserId(user.getId());
        signature.setTypedName(input.typedName().trim());
        signature.setStorageKey(key);
        signature.setContentType("image/png");
        signature.setSizeBytes(png.length);
        signature.setDocuments(new TreeMap<>(versions).entrySet().stream()
                .map(e -> e.getKey().id() + " " + e.getValue())
                .collect(Collectors.joining(", ")));
        // The server's clock, not the browser's: a signing time is evidence.
        signature.setSignedAt(Instant.now());
        signature.setDevice(device == null ? null : device.length() > 200 ? device.substring(0, 200) : device);
        signature.setIpAddress(ipAddress);
        return signatures.save(signature);
    }

    @Transactional(readOnly = true)
    public List<AgreementSignature> forUser(UUID userId) {
        return signatures.findByUserIdOrderBySignedAtDesc(userId);
    }

    /** A signed, expiring link for the admin panel, through the receipts endpoint that audits each view. */
    public String signedUrl(AgreementSignature signature) {
        return storage.signedUrl(signature.getStorageKey(), properties.storage().signedUrlTtl());
    }

    /** The image itself, for the customer's own copy. */
    public String dataUrl(AgreementSignature signature) {
        try (InputStream in = storage.open(signature.getStorageKey())) {
            return PREFIX + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
