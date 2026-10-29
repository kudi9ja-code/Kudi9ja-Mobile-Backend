package com.quadrilateral.kudi9ja.domain.loanapplication;

/** Where an application to borrow stands. */
public enum LoanApplicationStatus {

    /** Submitted, with its documents, waiting for a person to read it. */
    PENDING("Under review"),

    /**
     * The reviewing admin changed the terms and sent them back. Waiting for
     * the customer to agree, which returns it to {@link #PENDING}, or to turn
     * them down, which ends it.
     */
    OFFERED("New terms to answer"),

    /** Approved by an admin. The loan exists and the money has been disbursed. */
    APPROVED("Approved"),

    /**
     * Turned down by an admin, with a reason the customer is shown. Nothing
     * stops them fixing what was wrong and applying again — which is the point
     * of giving a reason rather than a refusal.
     */
    REJECTED("Declined"),

    /** Withdrawn by the customer before anybody had reviewed it. */
    CANCELLED("Withdrawn"),

    /** The customer turned down the terms the admin offered. Nothing was lent. */
    TERMS_DECLINED("Terms turned down");

    private final String label;

    LoanApplicationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Still waiting on someone: the team, or the customer's answer. */
    public boolean isOpen() {
        return this == PENDING || this == OFFERED;
    }
}
