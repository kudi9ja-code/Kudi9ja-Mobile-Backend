package com.quadrilateral.kudi9ja.domain.loanapplication;

/** Where an application to borrow stands. */
public enum LoanApplicationStatus {

    /** Submitted, with its documents, waiting for a person to read it. */
    PENDING("Under review"),

    /** Approved by an admin. The loan exists and the money has been disbursed. */
    APPROVED("Approved"),

    /**
     * Turned down by an admin, with a reason the customer is shown. Nothing
     * stops them fixing what was wrong and applying again — which is the point
     * of giving a reason rather than a refusal.
     */
    REJECTED("Declined"),

    /** Withdrawn by the customer before anybody had reviewed it. */
    CANCELLED("Withdrawn");

    private final String label;

    LoanApplicationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Still waiting on a decision, and so still editable by nobody. */
    public boolean isOpen() {
        return this == PENDING;
    }
}
