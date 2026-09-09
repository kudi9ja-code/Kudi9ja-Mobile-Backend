package com.quadrilateral.kudi9ja.integration.storage;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Receipts in Cloudinary.
 *
 * <p>Bound when {@code kudi9ja.storage.provider} is {@code cloudinary}. It
 * exists because Render's filesystem does not survive a deploy: with
 * {@link LocalReceiptStorage} every receipt uploaded since the last release is
 * gone the next time the service restarts, while the claims still point at
 * them. An admin would open a pending claim and find nothing to check it
 * against, which is the one thing the receipt is for.
 *
 * <h2>Privacy</h2>
 *
 * <p>Everything is uploaded with {@code type=authenticated}. That is the strict
 * setting: neither the original nor any derived version of it is reachable
 * without a signature, so no receipt has a public URL at any size. It is not
 * the default, and the default would be wrong here — a receipt is a photograph
 * of somebody's bank transfer, carrying their name, their account number and
 * often their balance.
 *
 * <p>Cloudinary's own signed URLs are never handed out either. {@code
 * signedUrl} returns a link to our admin endpoint exactly as the local backend
 * does, so opening a receipt still requires a live panel session and still
 * writes an audit entry. Cloudinary is a place to keep bytes and nothing more.
 *
 * <h2>Talking to the API</h2>
 *
 * <p>Two different authentication schemes, because Cloudinary uses two:
 *
 * <ul>
 *   <li><b>Upload</b> takes HTTP Basic with the key and secret, which is the
 *       documented server-side shortcut and needs no signature at all.
 *   <li><b>Download</b> has no Basic form. It takes a signature over the query
 *       parameters: sorted by name, joined with {@code &}, the API secret
 *       appended with no separator, SHA-1, hex. {@code api_key} is excluded
 *       from what is signed and sent alongside.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "kudi9ja.storage.provider", havingValue = "cloudinary")
public class CloudinaryReceiptStorage implements ReceiptStorage {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryReceiptStorage.class);
    private static final String API = "https://api.cloudinary.com/v1_1/";

    /**
     * How long a download URL we mint for our own immediate use is good for.
     * It is fetched within the same request, so this is a bound on a mistake
     * rather than a window anybody waits in.
     */
    private static final Duration DOWNLOAD_WINDOW = Duration.ofMinutes(2);

    /**
     * How long we are willing to wait on Cloudinary, and why there is a bound
     * at all.
     *
     * <p>The upload happens inside the customer's request, before the claim is
     * saved. Left untimed — which is the default, and what this was — a stalled
     * connection holds the request thread for ever: the phone gives up after a
     * minute and tells the customer their connection failed, while the thread
     * stays parked. Enough of those and the pool is gone and the whole service
     * is unreachable, for one slow upstream.
     *
     * <p>Read is the generous one: a receipt is a photograph on a mobile
     * uplink and Cloudinary is not always quick. It is still well inside the
     * phone's own 60-second timeout, so the customer gets a real error they can
     * act on rather than silence.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    private final Kudi9jaProperties.Cloudinary config;
    private final ReceiptUrlSigner urlSigner;
    private final RestClient http;

    public CloudinaryReceiptStorage(Kudi9jaProperties properties, ReceiptUrlSigner urlSigner) {
        this.config = properties.storage().cloudinary();
        this.urlSigner = urlSigner;
        this.http = RestClient.builder().requestFactory(timeoutFactory()).build();

        if (isBlank(config.cloudName()) || isBlank(config.apiKey()) || isBlank(config.apiSecret())) {
            throw new IllegalStateException(
                    "Receipt storage is set to cloudinary, but CLOUDINARY_CLOUD_NAME, "
                            + "CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET are not all set. "
                            + "Refusing to start rather than accept receipts with nowhere to put them.");
        }
        log.info("Receipts are stored in Cloudinary ({}), as authenticated assets under {}/",
                config.cloudName(), config.folder());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Storing
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String store(String ownerRef, String originalFilename, String contentType, byte[] content) {
        String extension = extensionFor(contentType, originalFilename);
        // The same key shape the local backend uses, so a claim's stored key
        // means the same thing either way and the admin endpoint keeps working
        // on receipts saved before the switch.
        String key = sanitise(ownerRef) + "/" + UUID.randomUUID() + extension;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = http.post()
                    .uri(API + config.cloudName() + "/" + resourceTypeFor(key) + "/upload")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(uploadBody(publicId(key), content, extension))
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("public_id") == null) {
                throw new ApiException(ErrorCode.INTERNAL,
                        "That receipt could not be saved. Try again.");
            }
        } catch (RestClientException e) {
            // Deliberately not surfacing the provider's message: it can carry
            // the cloud name and the key, and the customer can do nothing with
            // either.
            log.error("Cloudinary rejected a receipt upload for {}", ownerRef, e);
            throw new ApiException(ErrorCode.INTERNAL, "That receipt could not be saved. Try again.");
        }
        return key;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reading
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String signedUrl(String key, Duration ttl) {
        return urlSigner.signedUrl(key, ttl);
    }

    @Override
    public InputStream open(String key) {
        byte[] bytes;
        try {
            bytes = http.get()
                    .uri(privateDownloadUrl(key))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException e) {
            log.error("Cloudinary would not return the receipt at {}", key, e);
            throw ApiException.notFound("That receipt");
        }
        if (bytes == null || bytes.length == 0) {
            throw ApiException.notFound("That receipt");
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public boolean exists(String key) {
        try {
            http.get()
                    // A URI, not a String, for the reason given on
                    // privateDownloadUrl: the encoded public_id would be
                    // encoded a second time on the way out.
                    .uri(URI.create(API + config.cloudName() + "/resources/" + resourceTypeFor(key)
                            + "/authenticated/" + encode(publicId(key))))
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    /**
     * Removes an object, and only ever for a receipt whose five-year retention
     * has run out — the caller is responsible for that, as it is on the local
     * backend.
     */
    @Override
    public void delete(String key) {
        try {
            http.post()
                    .uri(API + config.cloudName() + "/" + resourceTypeFor(key) + "/destroy")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart(publicId(key)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // Matches the local backend: a receipt that will not delete is
            // logged rather than thrown, because the caller is a retention
            // sweep and one stuck object must not stop the rest of it.
            log.warn("Could not delete the receipt at {}", key, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Signing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds Cloudinary's private-download URL for one object.
     *
     * <p>Returns a {@link URI} rather than a {@code String}, and that is the
     * whole point of the type. {@code RestClient.uri(String)} takes its
     * argument as a <i>template</i> and encodes it on the way out, so a URL
     * that already carries {@code %2F} in a public_id went over the wire as
     * {@code %252F}; Cloudinary decoded it once, signed
     * {@code kudi9ja%2Freceipts%2F…} and refused every receipt with "Invalid
     * Signature". The signature was right the whole time — the transport
     * encoded it a second time. {@code uri(URI)} is passed through untouched,
     * so handing this back as a URI makes that mistake unavailable rather than
     * merely fixed.
     *
     * <p>Visible for testing: the signature is the part worth checking, and it
     * cannot be checked through a live call.
     */
    URI privateDownloadUrl(String key) {
        long now = Instant.now().getEpochSecond();

        // Sorted, because the signature is defined over the parameters in
        // alphabetical order. A TreeMap makes that a property of the structure
        // rather than something the next edit has to remember.
        Map<String, String> params = new TreeMap<>();
        params.put("public_id", publicId(key));
        params.put("format", formatFor(key));
        params.put("type", "authenticated");
        params.put("timestamp", Long.toString(now));
        params.put("expires_at", Long.toString(now + DOWNLOAD_WINDOW.toSeconds()));

        return downloadUri(
                API + config.cloudName() + "/" + resourceTypeFor(key) + "/download",
                params,
                signParameters(params),
                config.apiKey());
    }

    /**
     * The signed parameters, as one URI.
     *
     * <p>Separate and static so it can be tested without a Cloudinary account
     * or a whole properties tree. The signature had a test and the URL carrying
     * it did not, which is exactly where the encoding went wrong.
     */
    static URI downloadUri(
            String base, Map<String, String> params, String signature, String apiKey) {

        StringBuilder url = new StringBuilder(base).append('?');
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue())).append('&');
        }
        return URI.create(url.append("signature=").append(encode(signature))
                .append("&api_key=").append(encode(apiKey))
                .toString());
    }

    /**
     * Cloudinary's parameter signature.
     *
     * <p>{@code name=value} pairs sorted by name, joined with {@code &}, the
     * API secret appended with no separator, SHA-1, lower-case hex. Values go
     * in unencoded — signing the encoded form produces a signature the server
     * will not agree with.
     *
     * <p>Package-private so a test can pin it to the worked example in
     * Cloudinary's documentation, which is the only way to know it is right
     * without a live account.
     */
    static String signParameters(Map<String, String> params, String apiSecret) {
        List<String> pairs = new ArrayList<>();
        new TreeMap<>(params).forEach((name, value) -> {
            if (value != null && !value.isEmpty()) {
                pairs.add(name + "=" + value);
            }
        });
        return sha1Hex(String.join("&", pairs) + apiSecret);
    }

    private String signParameters(Map<String, String> params) {
        return signParameters(params, config.apiSecret());
    }

    private static String sha1Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign a Cloudinary request", e);
        }
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (config.apiKey() + ":" + config.apiSecret()).getBytes(StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keys
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The Cloudinary public_id for a key.
     *
     * <p>The extension is dropped, because Cloudinary keeps the format
     * separately and a public_id ending in {@code .jpg} produces an object
     * called {@code ....jpg.jpg}.
     */
    String publicId(String key) {
        String withoutExtension = key;
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        if (dot > slash && dot > 0) {
            withoutExtension = key.substring(0, dot);
        }
        return isBlank(config.folder()) ? withoutExtension : config.folder() + "/" + withoutExtension;
    }

    /** The extension, without the dot — Cloudinary calls this the format. */
    static String formatFor(String key) {
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        return (dot > slash && dot > 0) ? key.substring(dot + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    /**
     * Which of Cloudinary's resource types an object belongs to.
     *
     * <p>Derived from the key rather than stored, so it cannot drift out of
     * step with it. PDFs count as images to Cloudinary; HEIC does not, and goes
     * to {@code raw} where it is kept byte-for-byte instead of being refused.
     */
    static String resourceTypeFor(String key) {
        return switch (formatFor(key)) {
            case "jpg", "jpeg", "png", "webp", "pdf" -> "image";
            default -> "raw";
        };
    }

    private static Resource asResource(byte[] content, String filename) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /**
     * The body of an upload.
     *
     * <p>Visible for testing, and that is the whole point of it being a method.
     * This was written with {@code MultipartBodyBuilder}, which reads as the
     * obvious choice and is not: it is part of Spring's reactive story and
     * touching it loads {@code org.reactivestreams.Publisher}, which is not on
     * the classpath of a plain Web MVC service. Nothing said so at build time
     * or at startup — the class only fails when it is first used, so every
     * receipt upload died with a {@code NoClassDefFoundError} and the customer
     * was told something went wrong on our side.
     *
     * <p>A plain {@link LinkedMultiValueMap} of strings and one {@link Resource}
     * is written by the form converter that is already there, needs no
     * dependency, and cannot fail this way.
     */
    static MultiValueMap<String, Object> uploadBody(String publicId, byte[] content, String extension) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", asResource(content, "receipt" + extension));
        body.add("public_id", publicId);
        body.add("type", "authenticated");
        // Cloudinary would otherwise strip an unrecognised extension and hand
        // back a different format from the one the key claims.
        body.add("use_filename", "false");
        body.add("unique_filename", "false");
        body.add("overwrite", "false");
        return body;
    }

    private static MultiValueMap<String, Object> multipart(String publicId) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("public_id", publicId);
        body.add("type", "authenticated");
        body.add("invalidate", "true");
        return body;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sanitise(String value) {
        if (isBlank(value)) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    /**
     * The extension for an upload, from its declared content type and falling
     * back to its filename. Kept identical to the local backend so the same
     * upload produces the same key shape on either.
     */
    static String extensionFor(String contentType, String filename) {
        if (contentType != null) {
            switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/jpeg", "image/jpg" -> {
                    return ".jpg";
                }
                case "image/png" -> {
                    return ".png";
                }
                case "image/heic" -> {
                    return ".heic";
                }
                case "image/webp" -> {
                    return ".webp";
                }
                case "application/pdf" -> {
                    return ".pdf";
                }
                default -> {
                    // fall through to the filename
                }
            }
        }
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0 && dot < filename.length() - 1) {
                String candidate = filename.substring(dot).toLowerCase(Locale.ROOT);
                if (candidate.matches("\\.[a-z0-9]{1,5}")) {
                    return candidate;
                }
            }
        }
        return ".bin";
    }
}
