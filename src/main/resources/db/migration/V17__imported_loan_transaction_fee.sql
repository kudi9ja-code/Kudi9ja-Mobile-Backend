-- Loans entered by hand record their transaction fee on its own line, beside
-- the management fee, so what was paid out is exact: principal less both.
-- Rows entered before this had no such fee, so theirs is zero.

alter table imported_loan add column transaction_fee numeric(19, 2) not null default 0;
