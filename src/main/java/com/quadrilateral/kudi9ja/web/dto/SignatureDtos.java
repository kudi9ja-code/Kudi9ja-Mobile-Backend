package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.legal.AgreementSignature;
import java.time.Instant;
import java.util.UUID;

/** A signature given when agreements were accepted. */
public final class SignatureDtos {

    private SignatureDtos() {
    }

    /**
     * @param image a PNG data URL — on the customer's own view, so they have their copy
     * @param url   a signed, expiring link — on the admin's view, opened through the
     *              receipts endpoint that writes every view to the audit log
     */
    public record SignatureRecord(
            UUID id,
            String typedName,
            String documents,
            Instant signedAt,
            String device,
            String image,
            String url) {

        public static SignatureRecord forCustomer(AgreementSignature s, String image) {
            return new SignatureRecord(s.getId(), s.getTypedName(), s.getDocuments(), s.getSignedAt(), s.getDevice(), image, null);
        }

        public static SignatureRecord forAdmin(AgreementSignature s, String url) {
            return new SignatureRecord(s.getId(), s.getTypedName(), s.getDocuments(), s.getSignedAt(), s.getDevice(), null, url);
        }
    }
}
