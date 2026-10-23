-- Loan officers: the people on the team who look after a borrower.
--
-- Every loan customer has at least one, and may have several. A borrower who
-- has not signed up yet is looked after through their paper loan's file; when
-- they sign up and the loan attaches to their account, the officers move with
-- it. The officer's name and email are copied in, so the record still says
-- who it was after their panel access ends.

create table loan_officer_assignment (
    id                uuid          not null,
    user_id           uuid,
    imported_loan_id  uuid,
    officer_id        uuid          not null,
    officer_user_id   uuid          not null,
    officer_name      varchar(160)  not null,
    officer_email     varchar(254)  not null,
    is_lead           boolean       not null default false,
    assigned_at       timestamptz   not null,
    assigned_by       varchar(200)  not null,

    constraint pk_loan_officer_assignment primary key (id),
    constraint fk_loan_officer_user foreign key (user_id) references app_user (id),
    constraint fk_loan_officer_import foreign key (imported_loan_id) references imported_loan (id) on delete cascade,
    constraint fk_loan_officer_admin foreign key (officer_id) references admin_user (id),
    -- Exactly one of: a customer with an account, or a paper loan still waiting.
    constraint ck_loan_officer_target check ((user_id is null) <> (imported_loan_id is null)),
    constraint ux_loan_officer_user unique (user_id, officer_id),
    constraint ux_loan_officer_import unique (imported_loan_id, officer_id)
);

create index ix_loan_officer_officer on loan_officer_assignment (officer_id);
