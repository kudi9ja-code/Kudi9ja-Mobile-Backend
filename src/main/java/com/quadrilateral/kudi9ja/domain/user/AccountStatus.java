package com.quadrilateral.kudi9ja.domain.user;

/**
 * Whether an account may be used, and why not when it may not.
 *
 * <p>Closure is a soft delete. AML rules require identity and transaction
 * records to be kept for at least five years after the relationship ends, and a
 * deletion request cannot override that — so a closed account keeps its ledger
 * and loses its access, rather than being erased.
 */
public enum AccountStatus {

    ACTIVE("Active"),

    /** Flagged for review by an admin. Still usable; watched. */
    FLAGGED("Flagged for review"),

    /** Frozen by an admin. Signs in, but no money moves. */
    FROZEN("Frozen"),

    /**
     * No activity for twelve months. Frozen until the customer re-verifies,
     * having been notified first.
     */
    DORMANT("Dormant"),

    /** Closed at the customer's request, or by us. Records retained. */
    CLOSED("Closed"),

    /**
     * Opened for the customer by the team — a loan customer entered by an
     * admin — and not yet taken over by them. Everything works on it as on any
     * account (loans, schedules, repayments recorded by the team), but nobody
     * can sign in to it: the customer activates it by signing up with the BVN
     * the admin entered.
     */
    PENDING_ACTIVATION("Not activated yet");

    private final String label;

    AccountStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether the account may sign in at all. */
    public boolean canSignIn() {
        return this != CLOSED && this != PENDING_ACTIVATION;
    }

    /** Whether money may move on this account. */
    public boolean canTransact() {
        return this == ACTIVE || this == FLAGGED;
    }
}
