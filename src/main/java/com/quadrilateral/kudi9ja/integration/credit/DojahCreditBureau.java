package com.quadrilateral.kudi9ja.integration.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Credit score through Dojah ({@code GET /api/v1/fico_score?bvn=}).
 *
 * <p>Present whenever the Dojah keys are set, whatever {@code KYC_PROVIDER}
 * says. Each successful lookup is charged to the Dojah wallet.
 */
@Component
@ConditionalOnExpression(
        "!'${kudi9ja.kyc.dojah.secret-key:}'.isBlank() and !'${kudi9ja.kyc.dojah.app-id:}'.isBlank()")
public class DojahCreditBureau implements CreditBureau {

    private static final Logger log = LoggerFactory.getLogger(DojahCreditBureau.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final String appId;
    private final String secretKey;
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;

    public DojahCreditBureau(
            @Value("${kudi9ja.kyc.dojah.app-id}") String appId,
            @Value("${kudi9ja.kyc.dojah.secret-key}") String secretKey,
            @Value("${kudi9ja.kyc.dojah.base-url:https://api.dojah.io}") String baseUrl,
            ObjectMapper json) {
        this.appId = appId.trim();
        this.secretKey = secretKey.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public Report lookup(String bvn) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/fico_score?bvn=" + bvn))
                .timeout(TIMEOUT)
                .header("AppId", appId)
                .header("Authorization", secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return read(response.statusCode(), response.body());
        } catch (IOException e) {
            log.warn("Dojah credit lookup could not be reached: {}", e.getMessage());
            throw new ApiException(ErrorCode.INTERNAL, "Dojah could not be reached. Try again in a few minutes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL, "The credit check was interrupted. Try again.");
        }
    }

    Report read(int status, String body) {
        JsonNode root;
        try {
            root = json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            root = json.createObjectNode();
        }
        String error = root.path("error").asText("");

        if (status == 404) {
            return Report.noRecord();
        }
        if (status >= 200 && status < 300) {
            JsonNode score = root.path("entity").path("score");
            if (score.isMissingNode() || score.isNull()) {
                return Report.noRecord();
            }
            JsonNode fico = score.path("ficoScore");
            return new Report(
                    true,
                    integer(fico.path("score")),
                    text(fico.path("rating")),
                    text(fico.path("reasons")),
                    text(score.path("hasLoans")),
                    integer(score.path("totalNoOfDelinquentFacilities")),
                    text(score.path("lastReportedDate")));
        }

        log.error("Dojah credit lookup failed: HTTP {} {}", status, error);
        String message = switch (status) {
            case 401 -> "Dojah refused the keys. Check DOJAH_APP_ID and DOJAH_SECRET_KEY.";
            case 402 -> "The Dojah wallet is empty. Top it up, then try again.";
            case 424 -> "The credit bureaus are not answering just now. Try again later.";
            case 429 -> "Too many checks at once. Wait a minute and try again.";
            default -> "The credit check failed" + (error.isBlank() ? "." : ": " + error);
        };
        throw new ApiException(ErrorCode.INTERNAL, message);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer integer(JsonNode node) {
        if (node.isNumber()) {
            return node.asInt();
        }
        String s = text(node);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
