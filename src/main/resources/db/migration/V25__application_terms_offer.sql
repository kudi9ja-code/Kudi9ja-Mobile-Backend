-- Terms the reviewing admin changes on an application.
--
-- An admin may change the rate, the management fee, the transaction fee or the
-- months while reviewing. The changed set goes back to the customer, who agrees
-- or turns it down; only an agreed set is lent on. The four columns hold the
-- whole offer, changed or not, so the loan is priced from one place.

alter table loan_application
    add column proposed_months             integer,
    add column proposed_flat_rate          numeric(12, 6),
    add column proposed_management_fee     numeric(19, 2),
    add column proposed_repayment_fee_rate numeric(12, 6),
    add column proposal_note               varchar(1000),
    add column proposed_at                 timestamptz,
    add column proposed_by                 varchar(200),
    add column terms_agreed_at             timestamptz;

-- OFFERED: new terms are with the customer. TERMS_DECLINED: they turned them down.
alter table loan_application drop constraint ck_loan_application_status;
alter table loan_application add constraint ck_loan_application_status
    check (status in ('PENDING', 'OFFERED', 'APPROVED', 'REJECTED', 'CANCELLED', 'TERMS_DECLINED'));

-- Still one open application per customer, whichever side it is waiting on.
drop index ux_loanapp_one_pending;
create unique index ux_loanapp_one_pending
    on loan_application (user_id)
    where status in ('PENDING', 'OFFERED');
