package com.quadrilateral.kudi9ja.integration.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.integration.bank.BankAccountResolver;
import com.quadrilateral.kudi9ja.integration.bank.BankDirectory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Checks a BVN with Dojah.
 *
 * <p>Dojah returns the name and date of birth the bank holds against the BVN,
 * and {@code KycService} refuses a sign-up whose name or date of birth do not
 * match. So a BVN that belongs to somebody else is caught here.
 *
 * <p>Only the BVN goes to Dojah. The NIN is still format-checked only, and a
 * payout account is resolved through Paystack when Paystack is configured
 * (that resolver is {@code @Primary}); without it, the account's format is
 * checked and nothing more.
 *
 * <p>If Dojah cannot be reached, or refuses the request for a reason that is
 * not about the BVN (a wrong key, an empty wallet), the customer is told to try
 * again later. An unchecked BVN is never passed as checked.
 *
 * <p>Each lookup is charged to the Dojah wallet.
 */
@Component
@ConditionalOnProperty(name = "kudi9ja.kyc.provider", havingValue = "dojah")
public class DojahIdentityVerifier implements IdentityVerifier, BankAccountResolver {

    private static final Logger log = LoggerFactory.getLogger(DojahIdentityVerifier.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Dates as Dojah has been seen to send them. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    private static final String UNAVAILABLE =
            "We could not check your BVN just now. Please try again in a few minutes.";

    private final String appId;
    private final String secretKey;
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http;

    public DojahIdentityVerifier(
            @Value("${kudi9ja.kyc.dojah.app-id:}") String appId,
            @Value("${kudi9ja.kyc.dojah.secret-key:}") String secretKey,
            @Value("${kudi9ja.kyc.dojah.base-url:https://api.dojah.io}") String baseUrl,
            ObjectMapper json) {
        if (appId == null || appId.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "KYC_PROVIDER is dojah but DOJAH_APP_ID or DOJAH_SECRET_KEY is empty.");
        }
        this.appId = appId.trim();
        this.secretKey = secretKey.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        if (this.secretKey.startsWith("test_")) {
            log.warn("Dojah is in SANDBOX mode. BVNs are not checked against real records.");
        }
        log.info("BVNs are checked with Dojah at {}. NINs are format-checked only.", this.baseUrl);
    }

    @Override
    public Verification verifyBvn(String bvn) {
        if (!isDigits(bvn, 11)) {
            return Verification.failed("A BVN is eleven digits.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/kyc/bvn/full?bvn=" + bvn))
                .timeout(TIMEOUT)
                .header("AppId", appId)
                .header("Authorization", secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Dojah could not be reached: {}", e.getMessage());
            throw new ApiException(ErrorCode.INTERNAL, UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL, UNAVAILABLE);
        }
        return read(response.statusCode(), response.body());
    }

    /**
     * Turns Dojah's answer into a verification.
     *
     * <p>A 2xx with an entity is a found BVN. A 400 or 404 that speaks about
     * the BVN is a BVN that does not exist. Anything else is Dojah or our
     * account being unwell, and is not the customer's fault.
     */
    Verification read(int status, String body) {
        JsonNode root;
        try {
            root = json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            log.warn("Dojah answered {} with a body that is not JSON", status);
            throw new ApiException(ErrorCode.INTERNAL, UNAVAILABLE);
        }

        if (status >= 200 && status < 300) {
            JsonNode entity = root.path("entity");
            if (entity.isMissingNode() || entity.isNull() || text(entity, "first_name") == null) {
                return Verification.failed("That BVN was not found.");
            }
            return new Verification(
                    true,
                    text(entity, "first_name"),
                    text(entity, "last_name"),
                    text(entity, "middle_name"),
                    date(text(entity, "date_of_birth")),
                    text(entity, "phone_number1"),
                    null);
        }

        String error = root.path("error").asText("");
        String lower = error.toLowerCase(Locale.ROOT);
        boolean aboutTheBvn = (status == 400 || status == 404)
                && (lower.contains("bvn") || lower.contains("not found") || lower.contains("invalid"));
        if (aboutTheBvn) {
            return Verification.failed("That BVN was not found. Check the number and try again.");
        }

        // A wrong key, an empty wallet, an outage. Logged for us; the customer
        // gets a plain "try again".
        log.error("Dojah BVN lookup failed: HTTP {} {}", status, error);
        throw new ApiException(ErrorCode.INTERNAL, UNAVAILABLE);
    }

    /** Not sent to Dojah: the NIN keeps the format check it had before. */
    @Override
    public Verification verifyNin(String nin) {
        if (!isDigits(nin, 11)) {
            return Verification.failed("A NIN is eleven digits.");
        }
        return new Verification(
                true, SandboxIdentityVerifier.SANDBOX_MATCHES_CALLER,
                SandboxIdentityVerifier.SANDBOX_MATCHES_CALLER, null, null, null, null);
    }

    /**
     * Used only when Paystack is not configured; otherwise Paystack's resolver
     * is primary and checks the account holder's name.
     */
    @Override
    public Resolution resolve(String bankCode, String accountNumber) {
        if (!isDigits(accountNumber, 10)) {
            return Resolution.failed("An account number is ten digits.");
        }
        if (BankDirectory.byCode(bankCode).isEmpty()) {
            return Resolution.failed("We do not recognise that bank.");
        }
        return new Resolution(true, SandboxIdentityVerifier.SANDBOX_MATCHES_CALLER, bankCode, null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** An unreadable date is left out, so it is not compared, rather than refusing the customer. */
    static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next
            }
        }
        log.warn("Dojah sent a date of birth in an unknown format; it was not compared");
        return null;
    }

    private static boolean isDigits(String value, int length) {
        return value != null && value.length() == length && value.chars().allMatch(Character::isDigit);
    }
}
