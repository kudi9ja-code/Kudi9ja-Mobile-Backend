package com.quadrilateral.kudi9ja.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deployment configuration: the things that belong in secret config rather
 * than in the database or in source.
 *
 * <p>Product economics do not live here. Rates, limits and switches are
 * admin-tunable and are held in the platform settings document, which is
 * versioned, diffed and audited.
 */
@Validated
@ConfigurationProperties(prefix = "kudi9ja")
public record Kudi9jaProperties(
        Company company,
        Security security,
        Jwt jwt,
        Storage storage,
        Otp otp,
        Mail mail,
        Bootstrap bootstrap,
        Jobs jobs,
        Push push,
        Compliance compliance) {

    /** The legal entity that contracts with customers. Kudi9ja is its product. */
    public record Company(
            String legalName,
            String rcNumber,
            String productName,
            String registeredAddress,
            String supportEmail,
            String legalEmail,
            String privacyEmail,
            String supportPhone,
            List<String> whatsapp) {
    }

    /**
     * @param pepper           held in secret config, never in source. The Flutter
     *                         client shipped a literal pepper that is now in public
     *                         git history; this replaces it and must be rotated
     *                         independently of any code release.
     * @param argonIterations  argon2id cost parameters
     * @param maxPasscodeAttempts a floor the platform settings cannot go below
     */
    public record Security(
            @NotBlank String pepper,
            int argonSaltLength,
            int argonHashLength,
            int argonParallelism,
            int argonMemoryKb,
            int argonIterations,
            int maxPasscodeAttempts,
            Duration lockoutDuration,
            Duration signInThrottleWindow,
            int signInAttemptsPerWindow) {
    }

    public record Jwt(
            @NotBlank String secret,
            String issuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            Duration adminAccessTokenTtl) {
    }

    /**
     * Where receipts and exported data go. Receipts are private, served to
     * admins over a signed expiring URL, and kept for five years as part of the
     * transaction record.
     */
    public record Storage(
            String provider,
            String receiptDirectory,
            String exportDirectory,
            Duration signedUrlTtl,
            long maxReceiptBytes,
            List<String> allowedReceiptTypes,
            Cloudinary cloudinary) {
    }

    /**
     * Cloudinary, when {@code kudi9ja.storage.provider} is {@code cloudinary}.
     *
     * <p>Used only as a place to keep bytes. Receipts are uploaded with
     * {@code type=authenticated}, which means neither the original nor any
     * derived version of it has a public URL — the only way to a receipt is
     * still the admin endpoint, which checks the signature, checks that the
     * caller holds panel access right now, and writes an audit entry.
     *
     * <p>This matters more than it sounds. A receipt is a photograph of a bank
     * transfer: a customer's name, their account number and their balance. On
     * the default upload type those would sit on a public CDN URL that needs no
     * credential at all, and one leaked link would need no login to open.
     */
    public record Cloudinary(
            String cloudName,
            String apiKey,
            String apiSecret,
            String folder) {
    }

    /**
     * How email leaves the building.
     *
     * @param provider {@code resend}, {@code smtp}, or {@code none} — which
     *                 logs the message instead of sending it. A deployment
     *                 without email still runs; sign-up simply cannot complete,
     *                 because the one-time code never arrives.
     * @param apiKey   Resend's key. Secret configuration, never in source.
     * @param from     the address customers see. Must be on a domain verified
     *                 with the provider, or the message is silently dropped.
     */
    public record Mail(
            String provider,
            String apiKey,
            String from,
            String replyTo) {
    }

    public record Otp(
            int length,
            Duration ttl,
            int maxAttempts,
            int maxPerHour) {
    }

    /**
     * Deliberate seeding. The client makes the first account on a device the
     * owner; a server needs the first owner named in configuration instead.
     */
    public record Bootstrap(
            List<String> ownerEmails,
            boolean seedLegalDocuments) {
    }

    /**
     * How a notification reaches a phone that is not open.
     *
     * <p>{@code provider} empty means no push: notifications are still written
     * and still appear when the app is opened, which is where the product was
     * before push existed. That is a working state, not a broken one, so an
     * unconfigured deployment starts rather than refusing to.
     */
    /**
     * @param serviceAccountJson the key itself, for hosts with no writable disk
     *                           to put a file on. Takes precedence over
     *                           {@code serviceAccountFile} when set.
     */
    public record Push(
            String provider,
            String serviceAccountFile,
            String serviceAccountJson,
            String androidChannelId,
            int staleDeviceDays) {
    }

    public record Jobs(
            boolean enabled,
            int unmatchedPayInReturnDays,
            int dormancyMonths,
            int repaymentReminderDays) {
    }

    /**
     * Obligations the documents shipped in the app place on the server.
     */
    public record Compliance(
            int dataRequestResponseDays,
            int breachNotificationHours,
            int recordRetentionYears,
            int complaintAcknowledgementHours,
            int complaintInvestigationWorkingDays,
            int complaintResolutionDays,
            int changeNoticeDays,
            int collectionsStartHour,
            int collectionsEndHour) {
    }
}
