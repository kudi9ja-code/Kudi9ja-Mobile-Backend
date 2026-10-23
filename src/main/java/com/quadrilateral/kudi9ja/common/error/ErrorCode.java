package com.quadrilateral.kudi9ja.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every way a request can be refused, as a code the client can branch on.
 *
 * <p>The message on a thrown {@link ApiException} is what the customer reads,
 * so it explains the rule rather than naming the check that failed.
 */
public enum ErrorCode {

    // Generic ---------------------------------------------------------------
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    MAINTENANCE(HttpStatus.SERVICE_UNAVAILABLE),
    FEATURE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),

    // Auth and session ------------------------------------------------------
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    ACCOUNT_FROZEN(HttpStatus.FORBIDDEN),
    ACCOUNT_DORMANT(HttpStatus.FORBIDDEN),
    ACCOUNT_NOT_ACTIVATED(HttpStatus.CONFLICT),
    ACCOUNT_CLOSED(HttpStatus.FORBIDDEN),
    EMAIL_TAKEN(HttpStatus.CONFLICT),
    PHONE_TAKEN(HttpStatus.CONFLICT),
    BVN_TAKEN(HttpStatus.CONFLICT),
    NIN_TAKEN(HttpStatus.CONFLICT),
    UNDERAGE(HttpStatus.BAD_REQUEST),
    OTP_INVALID(HttpStatus.BAD_REQUEST),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST),
    OTP_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS),
    PIN_INVALID(HttpStatus.FORBIDDEN),
    PASSCODE_INVALID(HttpStatus.FORBIDDEN),
    PIN_REQUIRED(HttpStatus.FORBIDDEN),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST),
    KYC_FAILED(HttpStatus.BAD_REQUEST),
    KYC_TIER_TOO_LOW(HttpStatus.FORBIDDEN),
    NAME_MISMATCH(HttpStatus.BAD_REQUEST),
    LEGAL_ACCEPTANCE_REQUIRED(HttpStatus.BAD_REQUEST),

    // Permissions -----------------------------------------------------------
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_AN_ADMIN(HttpStatus.FORBIDDEN),
    SELF_LOCKOUT(HttpStatus.FORBIDDEN),
    LAST_OWNER(HttpStatus.FORBIDDEN),

    // Money -----------------------------------------------------------------
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    AMOUNT_TOO_SMALL(HttpStatus.BAD_REQUEST),
    AMOUNT_TOO_LARGE(HttpStatus.BAD_REQUEST),
    DAILY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    RECEIPT_REQUIRED(HttpStatus.BAD_REQUEST),
    NO_PAYOUT_ACCOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    ALREADY_REVIEWED(HttpStatus.CONFLICT),

    // Savings ---------------------------------------------------------------
    PLAN_NOT_MATURED(HttpStatus.UNPROCESSABLE_ENTITY),
    PLAN_CANNOT_BREAK(HttpStatus.UNPROCESSABLE_ENTITY),
    PLAN_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY),
    LOCK_TOO_SHORT(HttpStatus.BAD_REQUEST),
    LOCK_TOO_LONG(HttpStatus.BAD_REQUEST),
    TERM_TOO_SHORT(HttpStatus.BAD_REQUEST),

    // Lending ---------------------------------------------------------------
    NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_ENTITY),
    OFFER_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    TENURE_UNPRICED(HttpStatus.BAD_REQUEST),
    LOAN_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY),
    CANCELLATION_WINDOW_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY),

    // Thrift ----------------------------------------------------------------
    MEMBER_NOT_A_CUSTOMER(HttpStatus.BAD_REQUEST),
    CIRCLE_FULL(HttpStatus.CONFLICT),
    CIRCLE_COMPLETE(HttpStatus.UNPROCESSABLE_ENTITY),
    ALREADY_A_MEMBER(HttpStatus.CONFLICT),
    ALREADY_CONTRIBUTED(HttpStatus.CONFLICT),
    NOT_A_MEMBER(HttpStatus.FORBIDDEN),

    // Settings --------------------------------------------------------------
    SETTINGS_INVALID(HttpStatus.BAD_REQUEST),
    SETTINGS_STALE(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
