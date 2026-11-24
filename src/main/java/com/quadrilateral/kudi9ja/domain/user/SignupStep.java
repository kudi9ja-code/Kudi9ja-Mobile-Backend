package com.quadrilateral.kudi9ja.domain.user;

/**
 * The nine gated steps of signing up, in order.
 *
 * <p>Each is validated on the server as it is submitted, and a step may only be
 * submitted once the one before it has passed. The client runs the same
 * sequence, but the client is not what enforces it: a wizard is a convenience,
 * and anything a client can skip, it will.
 */
public enum SignupStep {

    /** Name, email, phone, date of birth and gender. Eighteen or over. */
    PERSONAL,

    /**
     * Email verified by a six-digit code. Email is the only channel verified;
     * the phone is collected so support can reach the customer, not as a
     * second factor.
     */
    EMAIL_VERIFIED,

    /** BVN, NIN, address and state, checked against the issuing institutions. */
    IDENTITY,

    /**
     * Face check: blink, turn the head, open the mouth. The movements are
     * checked on the device; one photo of each is kept for the admin panel.
     */
    LIVENESS,

    /** The customer's own bank account, resolved and name-matched. */
    PAYOUT,

    /** Password and security question. */
    PASSWORD,

    /** The six-digit sign-in passcode. */
    PASSCODE,

    /** The four-digit transaction PIN. */
    PIN,

    /** Reviewed and all three legal documents accepted. The account exists. */
    COMPLETE;

    /** Whether this draft has got at least as far as {@code required}. */
    public boolean reached(SignupStep required) {
        return ordinal() >= required.ordinal();
    }

    public SignupStep next() {
        return this == COMPLETE ? COMPLETE : values()[ordinal() + 1];
    }
}
