package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.support.SupportService;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** Files in and out of help-and-complaints conversations, for both sides' controllers. */
public final class SupportFiles {

    private SupportFiles() {
    }

    public static SupportService.Attachment attachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new SupportService.Attachment(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw ApiException.validation("That file could not be read. Try attaching it again.");
        }
    }

    public static ResponseEntity<InputStreamResource> serve(SupportService.AttachmentContent content) {
        MediaType type;
        try {
            type = content.contentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(content.contentType());
        } catch (IllegalArgumentException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.filename()).build().toString())
                // Not a shared cache's business, nor worth leaving in a browser profile.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(new InputStreamResource(content.content()));
    }
}
