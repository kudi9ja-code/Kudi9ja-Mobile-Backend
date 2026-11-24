package com.quadrilateral.kudi9ja.integration.credit;

/** A customer's credit score from the Nigerian credit bureaus, looked up by BVN. */
public interface CreditBureau {

    /** Each call may be charged by the provider. */
    Report lookup(String bvn);

    /**
     * @param found                 false when the bureaus hold no record for the BVN
     * @param score                 the score, when one was given
     * @param rating                the bureau's word for it, e.g. AVERAGE
     * @param reasons               why the score is what it is
     * @param hasLoans              YES or NO, as the bureau says
     * @param delinquentFacilities  loans reported as in arrears
     * @param lastReported          when a lender last reported, as the bureau writes it
     */
    record Report(
            boolean found,
            Integer score,
            String rating,
            String reasons,
            String hasLoans,
            Integer delinquentFacilities,
            String lastReported) {

        public static Report noRecord() {
            return new Report(false, null, null, null, null, null, null);
        }
    }
}
