-- Past loans are entered with every repayment that was made on them, each on
-- the day it was paid. Those repayments are part of the entry itself, so a
-- mistaken entry can still be taken back with them; repayments recorded later
-- are not, and keep the entry from being removed.
alter table recorded_repayment
    add column entered_with_loan boolean not null default false;
