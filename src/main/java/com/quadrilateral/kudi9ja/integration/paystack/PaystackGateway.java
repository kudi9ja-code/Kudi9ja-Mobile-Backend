package com.quadrilateral.kudi9ja.integration.paystack;

import java.time.Instant;
import java.util.Map;

/**
 * Paystack, as this application uses it: take a payment, confirm it, and send
 * money to a customer's bank account.
 *
 * <p>An interface so the domain never holds an HTTP client, and so the tests
 * can stand a fake in front of the real thing. There is no bean at all when no
 * secret key is configured — services take an {@code ObjectProvider} and fall
 * back to the manual flow — so a deployment without Paystack still runs.
 *
 * <p>Amounts are in kobo, as Paystack counts them.
 */
public interface PaystackGateway {

    /** Opens a checkout. The customer is sent to {@code authorizationUrl}. */
    Initialized initialize(String email, long amountKobo, String reference, String callbackUrl, Map<String, Object> metadata);

    /** What Paystack says happened to a payment. The only authority on whether it succeeded. */
    Charge verify(String reference);

    /** Registers a bank account to send money to. Returns the recipient code. */
    String createRecipient(String name, String accountNumber, String bankCode);

    /** Sends money from the Paystack balance to a recipient. */
    Transfer transfer(long amountKobo, String recipientCode, String reference, String reason);

    /**
     * Who owns a bank account: Paystack asks the bank for the name on it.
     * "Not found" is an answer; Paystack being unreachable throws.
     */
    AccountLookup resolveAccount(String accountNumber, String bankCode);

    /** Whether a webhook body really came from Paystack: HMAC-SHA512 of the raw body with the secret key. */
    boolean signatureValid(byte[] body, String signature);

    record Initialized(String authorizationUrl, String accessCode, String reference) {
    }

    /**
     * @param status Paystack's word for it: success, failed, abandoned, ongoing, pending, reversed
     */
    record Charge(
            String reference,
            String status,
            long amountKobo,
            String currency,
            Instant paidAt,
            String channel,
            String gatewayResponse,
            Long feesKobo,
            String cardType,
            String last4,
            String bank) {

        public boolean succeeded() {
            return "success".equalsIgnoreCase(status);
        }

        public boolean finallyFailed() {
            return "failed".equalsIgnoreCase(status) || "abandoned".equalsIgnoreCase(status)
                    || "reversed".equalsIgnoreCase(status);
        }
    }

    /** @param found whether the bank knows the account; {@code reason} says why not */
    record AccountLookup(boolean found, String accountName, String reason) {
    }

    /** @param status success, pending, processing, queued, otp, failed, reversed */
    record Transfer(String status, String transferCode, String reference) {
    }

    /** Paystack refused or could not be reached. The message is safe to show an admin. */
    class PaystackException extends RuntimeException {
        public PaystackException(String message) {
            super(message);
        }

        public PaystackException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
