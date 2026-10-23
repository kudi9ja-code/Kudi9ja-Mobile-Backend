package com.quadrilateral.kudi9ja.integration.paystack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Paystack over its REST API.
 *
 * <p>Present only when {@code kudi9ja.paystack.secret-key} is set. The key is
 * secret configuration and never logged; the same key signs webhooks, which is
 * how a webhook is known to be Paystack's and not somebody's guess at the URL.
 */
@Component
@ConditionalOnExpression("!'${kudi9ja.paystack.secret-key:}'.isBlank()")
public class HttpPaystackGateway implements PaystackGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaystackGateway.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String secretKey;
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;

    public HttpPaystackGateway(
            @Value("${kudi9ja.paystack.secret-key}") String secretKey,
            @Value("${kudi9ja.paystack.base-url:https://api.paystack.co}") String baseUrl,
            ObjectMapper json) {
        this.secretKey = secretKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        if (this.secretKey.startsWith("sk_test_")) {
            log.warn("Paystack is in TEST mode. No real money will move.");
        }
    }

    @Override
    public Initialized initialize(String email, long amountKobo, String reference, String callbackUrl,
            Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("amount", amountKobo);
        body.put("currency", "NGN");
        body.put("reference", reference);
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            body.put("callback_url", callbackUrl);
        }
        body.put("metadata", metadata);
        JsonNode data = call("POST", "/transaction/initialize", body).path("data");
        return new Initialized(
                data.path("authorization_url").asText(),
                data.path("access_code").asText(),
                data.path("reference").asText(reference));
    }

    @Override
    public Charge verify(String reference) {
        JsonNode data = call("GET", "/transaction/verify/" + encode(reference), null).path("data");
        JsonNode auth = data.path("authorization");
        String paidAt = text(data, "paid_at");
        return new Charge(
                data.path("reference").asText(reference),
                data.path("status").asText("pending"),
                data.path("amount").asLong(0),
                data.path("currency").asText("NGN"),
                instant(paidAt),
                text(data, "channel"),
                text(data, "gateway_response"),
                data.hasNonNull("fees") ? data.path("fees").asLong() : null,
                text(auth, "card_type"),
                text(auth, "last4"),
                text(auth, "bank"));
    }

    @Override
    public String createRecipient(String name, String accountNumber, String bankCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "nuban");
        body.put("name", name);
        body.put("account_number", accountNumber);
        body.put("bank_code", bankCode);
        body.put("currency", "NGN");
        return call("POST", "/transferrecipient", body).path("data").path("recipient_code").asText();
    }

    @Override
    public Transfer transfer(long amountKobo, String recipientCode, String reference, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "balance");
        body.put("amount", amountKobo);
        body.put("recipient", recipientCode);
        body.put("reference", reference);
        body.put("reason", reason);
        JsonNode data = call("POST", "/transfer", body).path("data");
        return new Transfer(data.path("status").asText("pending"), text(data, "transfer_code"), data.path("reference").asText(reference));
    }

    @Override
    public AccountLookup resolveAccount(String accountNumber, String bankCode) {
        String path = "/bank/resolve?account_number=" + encode(accountNumber) + "&bank_code=" + encode(bankCode);
        String unavailable = "We could not check that account with the bank just now. Try again in a minute.";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = response.body() == null || response.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(response.body());
            int status = response.statusCode();
            if (status >= 200 && status < 300 && root.path("status").asBoolean(false)) {
                String name = text(root.path("data"), "account_name");
                return name == null
                        ? new AccountLookup(false, null, "The bank did not return a name for that account.")
                        : new AccountLookup(true, name, null);
            }
            // 400 and 422 are how Paystack says there is no such account at that bank.
            if (status == 400 || status == 422) {
                return new AccountLookup(false, null,
                        "No account was found with that number at that bank. Check both and try again.");
            }
            log.warn("Paystack account lookup answered {}: {}", status, root.path("message").asText(""));
            throw new PaystackException(unavailable);
        } catch (IOException e) {
            throw new PaystackException(unavailable, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaystackException("The account check was interrupted.", e);
        }
    }

    @Override
    public boolean signatureValid(byte[] body, String signature) {
        if (signature == null || signature.isBlank() || body == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json");
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = response.body() == null || response.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300 && root.path("status").asBoolean(false)) {
                return root;
            }
            String message = root.path("message").asText("Paystack refused the request.");
            log.warn("Paystack {} {} answered {}: {}", method, path.replaceAll("/[^/]+$", "/…"), response.statusCode(), message);
            throw new PaystackException("Paystack said: " + message);
        } catch (IOException e) {
            throw new PaystackException("Paystack could not be reached. Try again in a moment.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaystackException("The request to Paystack was interrupted.", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    /** Paystack writes ISO-8601; anything else is not worth failing a payment over. */
    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
