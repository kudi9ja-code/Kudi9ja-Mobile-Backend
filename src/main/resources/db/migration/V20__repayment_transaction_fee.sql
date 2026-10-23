-- The transaction fee moves from the payout to the repayments.
--
-- Until now a flat ₦2,000 came off every loan as it was paid out. From here
-- nothing but the management fee comes off the payout; instead every
-- instalment carries a transaction fee of 2% of itself, shown to the borrower
-- before they apply and added to what they repay. Instalments and their fees
-- are rounded up to the whole naira.
--
-- Loans already running keep the terms they were given: their flat fee stays
-- on record, they carry no repayment fee, and their schedules are unchanged.

-- The rate, editable in the panel. The flat fee it replaces is gone.
alter table platform_settings add column loan_repayment_fee_rate numeric(12, 6) not null default 0.02;
alter table platform_settings add constraint ck_settings_repayment_fee_rate
    check (loan_repayment_fee_rate >= 0 and loan_repayment_fee_rate <= 0.2);
alter table platform_settings drop column loan_transaction_fee;

-- On each loan: how many instalments (fixed on the day it is paid out, since a
-- weekly count follows the calendar), the fee rate frozen onto it, the fees
-- that adds up to, and whether its instalments are in whole naira.
alter table loan add column installment_count integer;
update loan
   set installment_count = case
           when repayment_frequency = 'WEEKLY' then round(tenure_months * 52 / 12.0)
           else tenure_months
       end;
alter table loan alter column installment_count set not null;
alter table loan add constraint ck_loan_installment_count check (installment_count >= 1);

alter table loan add column repayment_fee_rate numeric(12, 6) not null default 0;
alter table loan add column repayment_fees numeric(19, 2) not null default 0;
alter table loan add column whole_naira boolean not null default false;
alter table loan add constraint ck_loan_repayment_fee_rate
    check (repayment_fee_rate >= 0 and repayment_fee_rate <= 0.2);
alter table loan add constraint ck_loan_repayment_fees check (repayment_fees >= 0);

-- A loan entered by hand carries the rate it was entered with.
alter table imported_loan add column repayment_fee_rate numeric(12, 6) not null default 0;
alter table imported_loan add constraint ck_imported_loan_repayment_fee_rate
    check (repayment_fee_rate >= 0 and repayment_fee_rate <= 0.2);
