package com.quadrilateral.kudi9ja.domain.loanapplication;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One uploaded file, as the application remembers it.
 *
 * <p>A key, not a path: the file lives in object storage and is served to an
 * admin over a signed URL that expires, exactly as a pay-in receipt is. The
 * content type and size are kept because the admin panel decides how to draw
 * the thing before it fetches it — a PDF bank statement and a photograph of a
 * shopfront are not shown the same way.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StoredDocument {

    @Column(name = "storage_key", nullable = false, length = 300)
    private String key;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;
}
