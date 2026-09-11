package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptUrlSigner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a receipt to an admin who holds a valid signed URL.
 *
 * <p>Receipts are private objects. They are never on a public path, and this is
 * the only route to one. Three separate things have to hold before a file is
 * returned, and none of them is redundant:
 *
 * <ol>
 *   <li><b>The signature is genuine and unexpired.</b> It is an HMAC over the
 *       key and the expiry, so a URL cannot be edited to reach another
 *       customer's receipt or to outlive its window. Compared in constant time,
 *       because a comparison that stops at the first mismatch lets a signature
 *       be recovered one character at a time.
 *   <li><b>The caller currently holds panel access.</b> Checked against the
 *       database on this request, not taken from the URL. A signed URL that
 *       leaks — pasted into a chat, left in a browser history — must not be a
 *       key to a customer's bank details in somebody else's hands.
 *   <li><b>The view is written to the audit log.</b> A receipt carries a
 *       customer's name and their bank details, and the Privacy Policy commits
 *       to being able to say who looked at one.
 * </ol>
 *
 * <p>The file is sent {@code inline} so it opens in the panel, and with a
 * {@code no-store} header so it does not sit in a shared browser cache
 * afterwards.
 */
@RestController
@RequestMapping("/api/v1/admin/receipts")
@Tag(name = "Admin — receipts", description = "Serving receipts over signed, expiring URLs")
public class AdminReceiptController {

    private final ReceiptStorage storage;
    private final ReceiptUrlSigner urlSigner;
    private final AdminAccessService access;
    private final AuditService audit;

    public AdminReceiptController(
            ReceiptStorage storage,
            ReceiptUrlSigner urlSigner,
            AdminAccessService access,
            AuditService audit) {
        this.storage = storage;
        this.urlSigner = urlSigner;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping("/{encodedKey}")
    @Operation(summary = "Fetch a receipt with a signed, unexpired URL. Audited.")
    public ResponseEntity<InputStreamResource> receipt(
            @PathVariable String encodedKey,
            @RequestParam long expires,
            @RequestParam String signature) {

        AdminUser actor = access.requireCanView();

        String key;
        try {
            key = new String(Base64.getUrlDecoder().decode(encodedKey), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("That receipt");
        }

        if (!urlSigner.isSignatureValid(key, expires, signature)) {
            // One message for a forged signature and for an expired one. Telling
            // them apart would say whether the key exists, and the panel's own
            // recovery is the same either way: reopen the claim for a fresh URL.
            throw ApiException.forbidden(
                    "That receipt link is no longer valid. Open the claim again for a fresh one.");
        }

        InputStream content = storage.open(key);

        audit.record(
                new AuditService.Actor(actor.getUserId(), actor.getName(), actor.getEmail()),
                AuditCategory.DATA_ACCESS,
                "Receipt viewed",
                actor.getName() + " opened the receipt stored at " + key + ".");

        return ResponseEntity.ok()
                .contentType(contentTypeFor(key))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filenameFor(key)).toString())
                // Not a shared cache's business, and not worth leaving on disk
                // in a browser profile after the window has closed.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(new InputStreamResource(content));
    }

    private static MediaType contentTypeFor(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".heic")) {
            return MediaType.parseMediaType("image/heic");
        }
        // A bank statement arrives as whatever the bank sent. Served as its
        // real type so the viewer opens it rather than downloading an opaque
        // blob — the admin has to read this, not collect it.
        if (lower.endsWith(".doc")) {
            return MediaType.parseMediaType("application/msword");
        }
        if (lower.endsWith(".docx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (lower.endsWith(".odt")) {
            return MediaType.parseMediaType("application/vnd.oasis.opendocument.text");
        }
        if (lower.endsWith(".csv")) {
            return MediaType.parseMediaType("text/csv");
        }
        if (lower.endsWith(".xls")) {
            return MediaType.parseMediaType("application/vnd.ms-excel");
        }
        if (lower.endsWith(".xlsx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String filenameFor(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }
}
