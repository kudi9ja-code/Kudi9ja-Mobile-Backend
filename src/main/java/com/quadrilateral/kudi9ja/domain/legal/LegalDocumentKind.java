package com.quadrilateral.kudi9ja.domain.legal;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import java.util.Locale;

/**
 * The three documents a customer accepts when they open an account.
 *
 * <p>All three are binding on Kudi9ja Limited, and the
 * backend has to make what they say true. The id matches the client's, so a
 * deep link into the app and a request to the API name the same thing.
 */
public enum LegalDocumentKind {

    TERMS("terms", "Terms of Service"),
    PRIVACY("privacy", "Privacy Policy"),
    LENDING("lending", "Lending Agreement");

    private final String id;
    private final String defaultTitle;

    LegalDocumentKind(String id, String defaultTitle) {
        this.id = id;
        this.defaultTitle = defaultTitle;
    }

    public String id() {
        return id;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    public static LegalDocumentKind fromId(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase(Locale.ROOT);
            for (LegalDocumentKind kind : values()) {
                if (kind.id.equals(wanted)) {
                    return kind;
                }
            }
        }
        throw ApiException.notFound("That document");
    }
}
