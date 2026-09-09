package com.quadrilateral.kudi9ja.integration.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.util.MultiValueMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Receipt storage, and the parts of Cloudinary that cannot be checked live.
 *
 * <p>The signature is the whole integration. Get it wrong and every upload is
 * refused with a message that says only "Invalid Signature", which is a slow
 * thing to debug against a remote service and an expensive one to discover
 * after a customer has already sent money. So it is pinned here against the
 * worked example in Cloudinary's own documentation, which is the one input
 * whose correct output is published.
 */
@DisplayName("Receipt storage")
class ReceiptStorageTest {

    @Nested
    @DisplayName("Cloudinary request signing")
    class Signing {

        /**
         * From Cloudinary's "Generating authentication signatures" page: these
         * three parameters, with the API secret {@code abcd}, produce this
         * digest. If this test fails, the algorithm has drifted — not the
         * expectation.
         */
        @Test
        @DisplayName("matches the worked example in Cloudinary's documentation")
        void matchesTheDocumentedExample() {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("timestamp", "1315060510");
            params.put("public_id", "sample_image");
            params.put("eager", "w_400,h_300,c_pad|w_260,h_200,c_crop");

            assertThat(CloudinaryReceiptStorage.signParameters(params, "abcd"))
                    .isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
        }

        /**
         * The parameters are signed in alphabetical order, not in the order
         * they were added. A caller that reorders them must not change the
         * signature, or the next edit to the parameter list breaks uploads
         * silently.
         */
        @Test
        @DisplayName("insertion order does not affect the signature")
        void orderIndependent() {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("timestamp", "1315060510");
            one.put("public_id", "sample_image");

            Map<String, String> other = new LinkedHashMap<>();
            other.put("public_id", "sample_image");
            other.put("timestamp", "1315060510");

            assertThat(CloudinaryReceiptStorage.signParameters(one, "abcd"))
                    .isEqualTo(CloudinaryReceiptStorage.signParameters(other, "abcd"));
        }

        /** An empty value is dropped rather than signed as {@code name=}. */
        @Test
        @DisplayName("empty and null values are left out")
        void emptyValuesDropped() {
            Map<String, String> withEmpty = new LinkedHashMap<>();
            withEmpty.put("public_id", "sample_image");
            withEmpty.put("timestamp", "1315060510");
            withEmpty.put("attachment", "");
            withEmpty.put("type", null);

            Map<String, String> without = new LinkedHashMap<>();
            without.put("public_id", "sample_image");
            without.put("timestamp", "1315060510");

            assertThat(CloudinaryReceiptStorage.signParameters(withEmpty, "abcd"))
                    .isEqualTo(CloudinaryReceiptStorage.signParameters(without, "abcd"));
        }

        @Test
        @DisplayName("a different secret gives a different signature")
        void secretMatters() {
            Map<String, String> params = Map.of("public_id", "sample_image");

            assertThat(CloudinaryReceiptStorage.signParameters(params, "abcd"))
                    .isNotEqualTo(CloudinaryReceiptStorage.signParameters(params, "abce"));
        }

        /**
         * The signature was right and every receipt still came back "Invalid
         * Signature", because the URL carrying it was encoded twice: it was
         * handed to {@code RestClient.uri(String)}, which treats its argument
         * as a template and encodes it again, so a public_id's {@code %2F} went
         * out as {@code %252F}. Cloudinary decoded once, signed
         * {@code kudi9ja%2Freceipts%2F…}, and disagreed.
         *
         * <p>Decoding the query value once must give the public_id back exactly
         * — that is what "encoded once" means, and it is the property the live
         * call depends on.
         */
        @Test
        @DisplayName("a public_id with slashes is encoded exactly once")
        void encodedExactlyOnce() {
            String publicId = "kudi9ja/receipts/K9-255B9A/c1a7ce24-7ce0-4701-a8ae-9782967d20d1";
            Map<String, String> params = new LinkedHashMap<>();
            params.put("public_id", publicId);
            params.put("format", "jpg");
            params.put("type", "authenticated");

            URI uri = CloudinaryReceiptStorage.downloadUri(
                    "https://api.cloudinary.com/v1_1/demo/image/download", params, "sig", "key");

            assertThat(queryValue(uri, "public_id")).isEqualTo(publicId);
            assertThat(queryValue(uri, "signature")).isEqualTo("sig");
            // The raw query still carries the escape — decoded once, not zero
            // times, is the whole point.
            assertThat(uri.getRawQuery()).contains("%2F").doesNotContain("%252F");
        }

        /** One decode of the raw query, which is what a server does. */
        private static String queryValue(URI uri, String name) {
            for (String pair : uri.getRawQuery().split("&")) {
                int equals = pair.indexOf('=');
                if (pair.substring(0, equals).equals(name)) {
                    return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                }
            }
            throw new AssertionError(name + " is not in " + uri);
        }
    }

    @Nested
    @DisplayName("Keys")
    class Keys {

        /**
         * Cloudinary keeps the format separately from the public_id. Leaving
         * the extension on produces an object called {@code ....jpg.jpg}, which
         * then cannot be fetched with the format the key implies.
         */
        @Test
        @DisplayName("the extension is stripped from the public_id")
        void extensionStripped() {
            assertThat(CloudinaryReceiptStorage.formatFor("K9-A1B2C3/abc-def.jpg")).isEqualTo("jpg");
            assertThat(CloudinaryReceiptStorage.formatFor("K9-A1B2C3/abc-def.pdf")).isEqualTo("pdf");
        }

        /**
         * A dot in a directory name is not an extension. Without this the
         * format would come back as a fragment of the folder.
         */
        @Test
        @DisplayName("a dot before the last slash is not an extension")
        void dotInDirectoryIgnored() {
            assertThat(CloudinaryReceiptStorage.formatFor("K9.OLD/abc-def")).isEqualTo("bin");
        }

        /**
         * PDFs are images to Cloudinary. HEIC is not, and is kept as a raw
         * object rather than being refused at upload — an iPhone photograph of
         * a bank receipt is the most common thing a customer will send.
         */
        @Test
        @DisplayName("resource type follows the format")
        void resourceType() {
            assertThat(CloudinaryReceiptStorage.resourceTypeFor("a/b.jpg")).isEqualTo("image");
            assertThat(CloudinaryReceiptStorage.resourceTypeFor("a/b.png")).isEqualTo("image");
            assertThat(CloudinaryReceiptStorage.resourceTypeFor("a/b.webp")).isEqualTo("image");
            assertThat(CloudinaryReceiptStorage.resourceTypeFor("a/b.pdf")).isEqualTo("image");
            assertThat(CloudinaryReceiptStorage.resourceTypeFor("a/b.heic")).isEqualTo("raw");
        }

        /**
         * The upload body is built with plain Spring types, and this test
         * exists to keep it that way.
         *
         * <p>It was built with {@code MultipartBodyBuilder}, which is the
         * obvious choice and belongs to Spring's reactive story: touching it
         * loads {@code org.reactivestreams.Publisher}, which a Web MVC service
         * does not have. Nothing failed at build time or at startup — the class
         * is only resolved the first time it is used, so the service came up
         * healthy and then threw {@code NoClassDefFoundError} at the first
         * customer to attach a receipt.
         *
         * <p>Calling it here is the whole guard: this suite runs on the same
         * classpath as the service, so anything that reaches for a dependency
         * we do not ship fails here rather than in somebody's hands.
         */
        @Test
        @DisplayName("the upload body needs nothing that is not on the classpath")
        void uploadBodyUsesNoReactiveTypes() {
            MultiValueMap<String, Object> body = CloudinaryReceiptStorage.uploadBody(
                    "kudi9ja/receipts/K9-000001/abc",
                    "not really a jpeg".getBytes(StandardCharsets.UTF_8),
                    ".jpg");

            assertThat(body.getFirst("public_id")).isEqualTo("kudi9ja/receipts/K9-000001/abc");
            // Authenticated, or the receipt has a public URL. See the class doc.
            assertThat(body.getFirst("type")).isEqualTo("authenticated");
            // Cloudinary renames the asset without these, and the stored key
            // would no longer find it.
            assertThat(body.getFirst("use_filename")).isEqualTo("false");
            assertThat(body.getFirst("unique_filename")).isEqualTo("false");
            assertThat(body.getFirst("overwrite")).isEqualTo("false");

            // The filename carries the extension, which is what Cloudinary
            // reads the format from.
            assertThat(body.getFirst("file")).isInstanceOf(Resource.class);
            assertThat(((Resource) body.getFirst("file")).getFilename()).isEqualTo("receipt.jpg");
        }

        /**
         * Both backends must produce the same key for the same upload, or a
         * claim stored under one cannot be opened after switching to the other.
         */
        @Test
        @DisplayName("the extension is chosen from the content type, as the local backend does")
        void extensionFromContentType() {
            assertThat(CloudinaryReceiptStorage.extensionFor("image/jpeg", "photo.bin"))
                    .isEqualTo(".jpg");
            assertThat(CloudinaryReceiptStorage.extensionFor("application/pdf", "x"))
                    .isEqualTo(".pdf");
            // Falls back to the filename when the content type says nothing.
            assertThat(CloudinaryReceiptStorage.extensionFor("application/octet-stream", "scan.PNG"))
                    .isEqualTo(".png");
            assertThat(CloudinaryReceiptStorage.extensionFor(null, "no-extension"))
                    .isEqualTo(".bin");
        }
    }
}
