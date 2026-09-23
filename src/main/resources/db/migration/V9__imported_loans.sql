-- Loans written on paper before the app existed.
--
-- For about a month the company lent by hand: a transfer from the company
-- account, a repayment schedule in a notebook. Those customers have not signed
-- up yet. An admin enters each loan here, against the customer's BVN, and the
-- moment that BVN is verified at signup the row is turned into an ordinary
-- loan on the new account — so the customer opens the app and finds what they
-- owe already there.
--
-- Nothing here touches the ledger. The money moved outside the app, so the
-- wallet stays at zero and only the loan book records the debt.
--
-- The BVN, email and phone are cleared the moment the row is claimed: the
-- account then holds them, and a second copy is one more place identity data
-- can leak from.

create table imported_loan (
    id              uuid           not null,

    -- Who. The BVN is the match key; the rest is for the admin's eye and the
    -- invitation to download the app.
    bvn             varchar(11),
    full_name       varchar(160)   not null,
    email           varchar(190),
    phone           varchar(20),

    -- The loan, exactly as it was agreed on paper.
    principal       numeric(19, 2) not null,
    tenure_months   integer        not null,
    flat_rate       numeric(12, 6) not null,
    processing_fee  numeric(19, 2) not null default 0,
    purpose         varchar(200)   not null,
    disbursed_at    timestamp(6) with time zone not null,
    amount_repaid   numeric(19, 2) not null default 0,

    imported_by     varchar(200)   not null,
    imported_at     timestamp(6) with time zone not null,

    -- Set once, when the customer signs up.
    claimed_at      timestamp(6) with time zone,
    user_id         uuid,
    loan_id         uuid,

    constraint pk_imported_loan primary key (id),
    constraint fk_imported_loan_user foreign key (user_id) references app_user (id),
    constraint fk_imported_loan_loan foreign key (loan_id) references loan (id),
    constraint ck_imported_loan_principal check (principal > 0),
    constraint ck_imported_loan_tenure check (tenure_months >= 1),
    constraint ck_imported_loan_repaid_not_negative check (amount_repaid >= 0),
    -- An unclaimed row must still know who it is waiting for.
    constraint ck_imported_loan_unclaimed_has_bvn
        check (claimed_at is not null or bvn is not null)
);

create index ix_imported_loan_bvn on imported_loan (bvn);
create index ix_imported_loan_claimed on imported_loan (claimed_at, imported_at);
