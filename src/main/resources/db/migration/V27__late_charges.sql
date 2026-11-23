-- Late charges on overdue loans.
--
-- From the day after a loan's final due date, 0.18% of the original amount
-- borrowed is added to what is owed, every day, until the loan is repaid in
-- full. It applies to every loan, including those taken before it existed,
-- but only counts days from the day it first went live: nothing is backdated.
--
-- late_charges is what has been added so far; late_charged_through is the last
-- Lagos day charged, so the hourly job never charges a day twice.

alter table loan add column late_charges numeric(19, 2) not null default 0;
alter table loan add column late_charged_through date;

alter table imported_loan add column late_charges numeric(19, 2) not null default 0;
alter table imported_loan add column late_charged_through date;

-- One row. starts_on is filled with the Lagos date the first time the server
-- starts with this migration in place: the deploy day, the first day charged.
create table late_charge_policy (
    id        integer not null,
    starts_on date,
    constraint pk_late_charge_policy primary key (id)
);

insert into late_charge_policy (id, starts_on) values (1, null);
