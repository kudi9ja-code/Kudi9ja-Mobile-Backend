-- Applying to borrow.
--
-- Borrowing used to be one POST: the server priced the loan, checked its own
-- records, credited the wallet and answered. The arithmetic was sound and still
-- runs, but it was the only thing between a stranger and a disbursement, and no
-- formula over our own ledger can say whether the business being lent against
-- exists. So a request became an application, carrying a bank statement, three
-- photographs of the premises and two guarantors, and a person decides it.
--
-- Nothing here holds money. The loan is still written by the loan table, by the
-- same code as always, at the moment an admin approves.

create table loan_application (
    id                      uuid            not null,
    user_id                 uuid            not null,

    -- Copied at submission so the admin queue reads without a join, which is
    -- how every other queue in this schema is built.
    customer_name           varchar(160)    not null,
    customer_ref            varchar(16)     not null,

    amount                  numeric(19, 2)  not null,
    tenure_months           integer         not null,
    purpose                 varchar(200)    not null,

    business_name           varchar(200)    not null,
    business_address        varchar(400)    not null,
    -- What the applicant says they earn. Their claim, checked against the
    -- statement by the person reading it — the gap between the two is often the
    -- most informative thing on the application.
    monthly_income          numeric(19, 2),

    -- The statement is a key into object storage, never a file on disk here.
    -- Served to admins over a signed, expiring URL, as receipts are.
    statement_key           varchar(300)    not null,
    statement_content_type  varchar(120),
    statement_size_bytes    bigint,

    status                  varchar(16)     not null,
    submitted_at            timestamptz     not null,
    reviewed_at             timestamptz,
    reviewed_by             varchar(200),
    -- Shown to the customer word for word. Required on a refusal, which the
    -- application layer enforces: a refusal somebody can act on brings them
    -- back with a better application.
    rejection_reason        varchar(1000),
    -- The loan this became. Null until approved.
    loan_id                 uuid,
    score_at_submission     integer,

    row_version             bigint          not null,

    constraint pk_loan_application primary key (id),
    constraint ck_loan_application_status
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    constraint ck_loan_application_amount check (amount > 0),
    constraint ck_loan_application_tenure check (tenure_months between 1 and 60),
    -- An approval without a loan, or a refusal with one, is a state that should
    -- not survive a bad deploy long enough to be read as either.
    constraint ck_loan_application_decided
        check ((status = 'APPROVED' and loan_id is not null)
            or (status <> 'APPROVED' and loan_id is null)),
    constraint ck_loan_application_reason
        check (status <> 'REJECTED' or rejection_reason is not null)
);

create index ix_loanapp_user on loan_application (user_id, submitted_at);
create index ix_loanapp_status on loan_application (status, submitted_at);

-- One application at a time per customer. A second while the first is unread is
-- the same request sent again because nothing visible happened, and it doubles
-- the queue without adding to it. Enforced here as well as in the service: a
-- double-tap racing itself past an exists() check is exactly the case a unique
-- index is for.
create unique index ux_loanapp_one_pending
    on loan_application (user_id)
    where status = 'PENDING';

-- Photographs of the business premises, in upload order. A table rather than
-- three columns on the parent: three is what is asked for today.
create table loan_application_photo (
    application_id  uuid            not null,
    position        integer         not null,
    storage_key     varchar(300)    not null,
    content_type    varchar(120),
    size_bytes      bigint,

    constraint pk_loan_application_photo primary key (application_id, position),
    constraint fk_loanapp_photo_application
        foreign key (application_id) references loan_application (id) on delete cascade
);

create index ix_loanapp_photo on loan_application_photo (application_id);

-- The two people vouching for the borrower.
--
-- Their BVN is recorded, not verified. Verifying one means asking the issuer
-- about a person, and we have consent from our customer for their own number
-- only — the borrower's word that a guarantor agreed is not the guarantor's
-- consent. It is here for identification if the loan ever has to be pursued.
create table loan_application_guarantor (
    application_id  uuid            not null,
    position        integer         not null,
    full_name       varchar(160)    not null,
    phone           varchar(20)     not null,
    address         varchar(400)    not null,
    relationship    varchar(120)    not null,
    bvn             varchar(11)     not null,
    occupation      varchar(160),
    email           varchar(200),

    constraint pk_loan_application_guarantor primary key (application_id, position),
    constraint fk_loanapp_guarantor_application
        foreign key (application_id) references loan_application (id) on delete cascade
);

create index ix_loanapp_guarantor on loan_application_guarantor (application_id);
