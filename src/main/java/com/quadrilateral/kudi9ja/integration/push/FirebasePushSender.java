package com.quadrilateral.kudi9ja.integration.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Firebase Cloud Messaging, over the HTTP v1 API.
 *
 * <p>Written against the REST API with a service-account key rather than
 * pulling in the Firebase Admin SDK. The SDK brings gRPC, Guava and a large
 * dependency tree to do what is, in the end, one signed JWT and one POST per
 * device — and a dependency that large in a money service is a supply-chain
 * surface for very little.
 *
 * <p>FCM has no true multicast on the v1 API: a batch is a loop. The loop is
 * bounded by {@link #MAX_DEVICES_PER_NOTIFICATION} because one customer with a
 * hundred stale tokens should not hold a request open, and because a customer
 * genuinely signed in on more than a few devices is a security question rather
 * than a delivery one.
 */
@Component
@ConditionalOnProperty(name = "kudi9ja.push.provider", havingValue = "firebase")
public class FirebasePushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushSender.class);

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    /** Beyond this a customer has a token-hygiene problem, not a delivery one. */
    private static final int MAX_DEVICES_PER_NOTIFICATION = 12;

    /**
     * Short, because nothing here is worth waiting for. The notification is
     * already in the database and the customer will read it when they open the
     * app; a push that has not left in five seconds has failed. Untimed — the
     * default — twelve stalled sends would hold a thread for ever.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http = RestClient.builder().requestFactory(timeoutFactory()).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Kudi9jaProperties properties;

    private final String projectId;
    private final String clientEmail;
    private final PrivateKey privateKey;

    /** Access tokens last an hour; this avoids minting one per notification. */
    private volatile String cachedAccessToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    public FirebasePushSender(Kudi9jaProperties properties) {
        this.properties = properties;
        String inline = properties.push().serviceAccountJson();
        boolean fromEnvironment = inline != null && !inline.isBlank();
        String where = fromEnvironment
                ? "the FIREBASE_SERVICE_ACCOUNT_JSON environment variable"
                : properties.push().serviceAccountFile();
        try {
            // The key as text, from wherever it was put. A managed host often
            // has no writable disk to hold a file, so the whole JSON can be
            // pasted into an environment variable instead.
            String raw = fromEnvironment
                    ? inline
                    : Files.readString(Path.of(properties.push().serviceAccountFile()),
                            StandardCharsets.UTF_8);
            JsonNode key = json.readTree(raw);
            this.projectId = key.get("project_id").asText();
            this.clientEmail = key.get("client_email").asText();
            this.privateKey = readPrivateKey(key.get("private_key").asText());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the Firebase service account from " + where
                            + ". Push is configured but cannot start.", e);
        }
        log.info("Push notifications will be delivered through Firebase project {} (key from {})",
                projectId, where);
    }

    @Override
    public Result send(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens == null || tokens.isEmpty()) {
            return Result.none();
        }

        String accessToken;
        try {
            accessToken = accessToken();
        } catch (Exception e) {
            // Logged, never thrown. The notification is already in the database.
            log.error("Could not authenticate with Firebase; push skipped", e);
            return Result.none();
        }

        String url = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";
        List<String> invalid = new ArrayList<>();
        int delivered = 0;

        for (String token : tokens.stream().limit(MAX_DEVICES_PER_NOTIFICATION).toList()) {
            try {
                http.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(message(token, title, body, data))
                        .retrieve()
                        .toBodilessEntity();
                delivered++;
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (isPermanentlyInvalid(e.getStatusCode())) {
                    // The app was uninstalled, or the token rotated. Deleting
                    // beats retrying it every time this customer is notified.
                    invalid.add(token);
                } else {
                    log.warn("Firebase refused a message: {}", e.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("Could not deliver a push notification", e);
            }
        }

        return new Result(delivered, invalid);
    }

    /**
     * {@code 404} means the token is gone; {@code 400} means it was never
     * valid. Everything else — rate limits, outages — is temporary and the
     * token is kept.
     */
    private static boolean isPermanentlyInvalid(HttpStatusCode status) {
        return status.value() == 404 || status.value() == 400;
    }

    private Map<String, Object> message(
            String token, String title, String body, Map<String, String> data) {

        return Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", title, "body", body),
                // Read by the app when the notification is tapped, so it opens
                // the plan or the claim rather than the home screen.
                "data", data == null ? Map.of() : data,
                "android", Map.of("priority", "high", "notification", Map.of(
                        "channel_id", properties.push().androidChannelId(),
                        "sound", "default")),
                "apns", Map.of("payload", Map.of("aps", Map.of(
                        "sound", "default",
                        // Lets iOS show the message on a locked screen without
                        // the app having been opened since it was installed.
                        "mutable-content", 1)))));
    }

    /** A cached OAuth token, minted from the service account when it lapses. */
    private synchronized String accessToken() throws Exception {
        if (cachedAccessToken != null && Instant.now().isBefore(cachedUntil)) {
            return cachedAccessToken;
        }

        String assertion = signedJwt();
        JsonNode response = json.readTree(http.post()
                .uri(TOKEN_URI)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + assertion)
                .retrieve()
                .body(String.class));

        cachedAccessToken = response.get("access_token").asText();
        // Renewed a minute early, so a token never expires mid-request.
        cachedUntil = Instant.now().plusSeconds(response.get("expires_in").asLong() - 60);
        return cachedAccessToken;
    }

    private String signedJwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = base64(json.writeValueAsBytes(
                Map.of("alg", "RS256", "typ", "JWT")));
        String claims = base64(json.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", SCOPE,
                "aud", TOKEN_URI,
                "iat", now,
                "exp", now + 3600)));

        String unsigned = header + "." + claims;
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(privateKey);
        rsa.update(unsigned.getBytes(StandardCharsets.UTF_8));
        return unsigned + "." + base64(rsa.sign());
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PrivateKey readPrivateKey(String pem) throws Exception {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }
}
