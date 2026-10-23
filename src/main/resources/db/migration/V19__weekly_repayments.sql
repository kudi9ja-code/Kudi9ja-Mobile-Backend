-- Weekly repayments, beside monthly.
--
-- A borrower chooses how often to pay back. The tenure is still in months and
-- priced from the same rate card; weekly only splits the same total into one
-- instalment every seven days. Every loan before this was monthly, so that is
-- what they all keep.

alter table loan add column repayment_frequency varchar(16) not null default 'MONTHLY';
alter table loan add constraint ck_loan_repayment_frequency
    check (repayment_frequency in ('MONTHLY', 'WEEKLY'));

alter table loan_application add column repayment_frequency varchar(16) not null default 'MONTHLY';
alter table loan_application add constraint ck_loan_application_repayment_frequency
    check (repayment_frequency in ('MONTHLY', 'WEEKLY'));

alter table imported_loan add column repayment_frequency varchar(16) not null default 'MONTHLY';
alter table imported_loan add constraint ck_imported_loan_repayment_frequency
    check (repayment_frequency in ('MONTHLY', 'WEEKLY'));
