package com.quadrilateral.kudi9ja.domain.legal;

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
 * The signature a customer drew, and the name they typed, when they accepted
 * their agreements. Append-only: a signature is evidence, and evidence is not
 * edited. A later signature — on a new version — is a new row.
 */
@Entity
@Table(
        name = "agreement_signature",
        indexes = @Index(name = "ix_agreement_signature_user", columnList = "user_id, signed_at"))
@Getter
@Setter
@NoArgsConstructor
public class AgreementSignature {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "typed_name", nullable = false, length = 160, updatable = false)
    private String typedName;

    @Column(name = "storage_key", nullable = false, length = 300, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 60, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "documents", nullable = false, length = 300, updatable = false)
    private String documents;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private Instant signedAt;

    @Column(name = "device", length = 200, updatable = false)
    private String device;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;
}
