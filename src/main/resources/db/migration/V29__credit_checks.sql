-- Credit bureau checks, fetched by an admin from Dojah using the customer's BVN.
--
-- Every check is kept, newest shown first, so the panel can show how a
-- customer's score has moved and who fetched it. Admin panel only; the
-- customer never sees this. found = false means the bureaus hold no record.

create table credit_check (
    id                       uuid          not null,
    user_id                  uuid          not null,
    checked_at               timestamptz   not null,
    checked_by               varchar(160)  not null,
    found                    boolean       not null,
    score                    integer,
    rating                   varchar(60),
    reasons                  text,
    has_loans                varchar(20),
    delinquent_facilities    integer,
    last_reported            varchar(40),

    constraint pk_credit_check primary key (id),
    constraint fk_credit_check_user foreign key (user_id) references app_user (id)
);

create index ix_credit_check_user on credit_check (user_id, checked_at desc);
