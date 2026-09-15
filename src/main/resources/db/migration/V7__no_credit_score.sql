-- No credit score, and no automated offer.
--
-- Kudi9ja lends on a person's reading of the application: the bank statement,
-- the business, the guarantor. A customer asks for what they need, between the
-- smallest and the largest loan the company writes, and the admin decides. A
-- score out of 850 built from savings habits stood in front of that and turned
-- customers away before anybody had read their file.
--
-- The columns behind the score and the offer formula go with it. Dropping
-- rather than leaving them: a column nothing writes to is a number an admin
-- will one day find on a screen and believe.

alter table platform_settings
    drop constraint ck_settings_score_band;

alter table platform_settings
    drop column loan_base_cap,
    drop column loan_savings_multiple,
    drop column loan_score_baseline,
    drop column loan_score_per_point,
    drop column loan_offer_rounding,
    drop column credit_base_score,
    drop column credit_points_per_plan,
    drop column credit_plan_points_cap,
    drop column credit_naira_per_savings_point,
    drop column credit_savings_points_cap,
    drop column credit_points_per_repaid_loan,
    drop column credit_repaid_points_cap,
    drop column credit_overdue_penalty,
    drop column credit_verified_bonus,
    drop column credit_score_floor,
    drop column credit_score_ceiling;

alter table loan
    drop column score_at_decision;

alter table loan_application
    drop column score_at_submission;
