package com.quadrilateral.kudi9ja.domain.loan;

/** Where a loan stands. */
public enum LoanStatus {

    /** Requested, awaiting an underwriting decision. */
    PENDING("Under review"),

    /** Disbursed and being repaid. */
    ACTIVE("Active"),

    /** Cleared in full, on schedule or early. */
    REPAID("Fully repaid"),

    /**
     * Past its due date with a balance outstanding. The amount owed does not
     * grow: there is no late fee and no penalty interest anywhere in this
     * product, and the Lending Agreement commits to that in writing.
     */
    OVERDUE("Overdue"),

    /** Declined at underwriting. The reasons are recorded and reviewable. */
    REJECTED("Declined"),

    /**
     * Cancelled inside the change-of-mind window that loans once had. No new
     * loan reaches this state: a loan in the wallet is repaid in full, with
     * interest. Kept for the loans already cancelled.
     */
    CANCELLED("Cancelled"),

    /** Written off by an admin. Still owed in principle, no longer pursued. */
    WRITTEN_OFF("Written off");

    private final String label;

    LoanStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** A loan still being repaid. */
    public boolean isOpen() {
        return this == ACTIVE || this == OVERDUE;
    }
}
