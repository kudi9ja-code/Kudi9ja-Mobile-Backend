-- The whole file on a loan made outside the app.
--
-- V9 recorded the debt: who, how much, over how long, how much was paid. That
-- is enough to put the loan on an account, but not enough to lend against. An
-- admin entering a borrower who has not signed up needs the same file an
-- in-app application builds: where they live, the business, a guarantor, and
-- the documents — the statement, the ID, the photographs, the signed form.
--
-- Four additions:
--
--   * the borrower's details, on imported_loan itself;
--   * imported_loan_guarantor, one row per guarantor;
--   * loan_document, a document attached by an admin to an imported loan or to
--     any loan on an account;
--   * recorded_repayment, a repayment that arrived outside the app — cash, a
--     transfer to the company account — entered by an admin. Like V9, these
--     never touch the ledger: the money did not pass through a wallet.
--
-- Nothing here is cleared at claim except what V9 already clears (BVN, email,
-- phone) and the NIN and date of birth added below: the account holds those
-- from then on. The rest is the loan's file and stays with the loan.

alter table imported_loan
    add column nin                    varchar(11),
    add column date_of_birth          date,
    add column gender                 varchar(40),
    add column address                varchar(400),
    add column state                  varchar(60),
    add column occupation             varchar(160),
    add column business_name          varchar(200),
    add column business_address       varchar(400),
    add column monthly_income         numeric(19, 2),
    add column payout_bank            varchar(120),
    add column payout_account_number  varchar(10),
    add column notes                  varchar(2000);

create index ix_imported_loan_user on imported_loan (user_id);
create index ix_imported_loan_loan on imported_loan (loan_id);

create table imported_loan_guarantor (
    imported_loan_id  uuid          not null,
    position          integer       not null,
    full_name         varchar(160)  not null,
    phone             varchar(20),
    address           varchar(400),
    state             varchar(60),
    local_government  varchar(120),
    landmark          varchar(200),
    relationship      varchar(120),
    bvn               varchar(11),
    occupation        varchar(160),
    email             varchar(200),

    constraint pk_imported_loan_guarantor primary key (imported_loan_id, position),
    constraint fk_imported_loan_guarantor_loan
        foreign key (imported_loan_id) references imported_loan (id) on delete cascade
);

create index ix_imported_loan_guarantor on imported_loan_guarantor (imported_loan_id);

create table loan_document (
    id                 uuid          not null,
    imported_loan_id   uuid,
    loan_id            uuid,
    label              varchar(120)  not null,
    -- A key into object storage, served to admins over a signed, expiring URL
    -- exactly as receipts and statements are.
    storage_key        varchar(300)  not null,
    content_type       varchar(120),
    size_bytes         bigint,
    original_filename  varchar(200),
    uploaded_by        varchar(200)  not null,
    uploaded_at        timestamp(6) with time zone not null,

    constraint pk_loan_document primary key (id),
    constraint fk_loan_document_import
        foreign key (imported_loan_id) references imported_loan (id) on delete cascade,
    constraint fk_loan_document_loan foreign key (loan_id) references loan (id),
    constraint ck_loan_document_owner check (imported_loan_id is not null or loan_id is not null)
);

create index ix_loan_document_import on loan_document (imported_loan_id, uploaded_at);
create index ix_loan_document_loan on loan_document (loan_id, uploaded_at);

create table recorded_repayment (
    id                uuid           not null,
    imported_loan_id  uuid,
    loan_id           uuid,
    amount            numeric(19, 2) not null,
    paid_at           timestamp(6) with time zone not null,
    method            varchar(40)    not null,
    reference         varchar(120),
    note              varchar(500),
    recorded_by       varchar(200)   not null,
    recorded_at       timestamp(6) with time zone not null,

    constraint pk_recorded_repayment primary key (id),
    constraint fk_recorded_repayment_import
        foreign key (imported_loan_id) references imported_loan (id) on delete cascade,
    constraint fk_recorded_repayment_loan foreign key (loan_id) references loan (id),
    constraint ck_recorded_repayment_amount check (amount > 0),
    constraint ck_recorded_repayment_owner check (imported_loan_id is not null or loan_id is not null)
);

create index ix_recorded_repayment_import on recorded_repayment (imported_loan_id, paid_at);
create index ix_recorded_repayment_loan on recorded_repayment (loan_id, paid_at);
