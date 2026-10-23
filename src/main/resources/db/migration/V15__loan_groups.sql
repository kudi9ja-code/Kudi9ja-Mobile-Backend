-- Group loans: Group 1, Group 2, … each named for where its borrowers are —
-- a market, a cooperative, a street — and run by one loan officer from the
-- team.
--
-- A member is a borrower like any other: either a customer with an account,
-- or a paper loan whose borrower has not signed up yet. When they sign up, the
-- membership gains their account and keeps its paper loan, so the group's
-- history stays whole.

create table loan_group (
    id                 uuid          not null,
    number             integer       not null,
    name               varchar(120)  not null,
    area               varchar(200),
    description        varchar(1000),
    officer_id         uuid          not null,
    officer_user_id    uuid          not null,
    officer_name       varchar(160)  not null,
    officer_email      varchar(254)  not null,
    active             boolean       not null default true,
    created_at         timestamptz   not null,
    created_by         varchar(200)  not null,

    constraint pk_loan_group primary key (id),
    constraint ux_loan_group_number unique (number),
    constraint fk_loan_group_officer foreign key (officer_id) references admin_user (id),
    constraint ck_loan_group_number check (number > 0)
);

create table loan_group_member (
    id                 uuid          not null,
    group_id           uuid          not null,
    user_id            uuid,
    imported_loan_id   uuid,
    added_at           timestamptz   not null,
    added_by           varchar(200)  not null,

    constraint pk_loan_group_member primary key (id),
    constraint fk_loan_group_member_group foreign key (group_id) references loan_group (id) on delete cascade,
    constraint fk_loan_group_member_user foreign key (user_id) references app_user (id),
    constraint fk_loan_group_member_import foreign key (imported_loan_id) references imported_loan (id) on delete cascade,
    constraint ck_loan_group_member_who check (user_id is not null or imported_loan_id is not null)
);

create index ix_loan_group_member_group on loan_group_member (group_id);
create index ix_loan_group_member_user on loan_group_member (user_id);
create index ix_loan_group_member_import on loan_group_member (imported_loan_id);
