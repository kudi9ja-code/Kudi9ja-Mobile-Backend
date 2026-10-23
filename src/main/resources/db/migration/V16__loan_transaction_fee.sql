-- A transaction fee on every loan, beside the management fee: ₦2,000 by
-- default, editable in the panel, deducted from the disbursement with the
-- management fee and shown as its own line. It covers what the payment
-- provider charges to move the money.
--
-- Loans already running were priced without it, so theirs is zero: a fee
-- never applies retrospectively.

alter table platform_settings add column loan_transaction_fee numeric(19, 2) not null default 2000;

alter table loan add column transaction_fee numeric(19, 2) not null default 0;
