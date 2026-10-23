package com.quadrilateral.kudi9ja.domain.loan;

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
 * A document an admin attached to a loan's file — a statement, an ID card, a
 * photograph of the shop, the signed paper agreement.
 *
 * <p>Attached either to an imported loan that is still waiting for its
 * borrower, or to a loan on an account. An imported loan's documents keep
 * their link to it after the claim; the loan finds them through the import.
 * The file itself lives in object storage, served over a signed, expiring URL.
 */
@Entity
@Table(
        name = "loan_document",
        indexes = {
                @Index(name = "ix_loan_document_import", columnList = "imported_loan_id, uploaded_at"),
                @Index(name = "ix_loan_document_loan", columnList = "loan_id, uploaded_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanDocument {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "imported_loan_id", updatable = false)
    private UUID importedLoanId;

    @Column(name = "loan_id", updatable = false)
    private UUID loanId;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "storage_key", nullable = false, length = 300, updatable = false)
    private String storageKey;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "original_filename", length = 200)
    private String originalFilename;

    @Column(name = "uploaded_by", nullable = false, length = 200)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;
}
