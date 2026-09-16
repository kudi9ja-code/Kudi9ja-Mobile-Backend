-- Ratings and reviews of the app.
--
-- One row per customer, rewritten in place when they change their mind, and
-- readable by every signed-in customer: the point of asking is that the
-- answer is public. The name shown is a first name and last initial, copied
-- at the time of writing; the full name stays on the account.

create table app_review (
    id            uuid          not null,
    user_id       uuid          not null,
    display_name  varchar(80)   not null,
    rating        integer       not null,
    comment       varchar(500)  not null,
    created_at    timestamptz   not null,
    updated_at    timestamptz   not null,

    constraint pk_app_review primary key (id),
    constraint fk_app_review_user foreign key (user_id) references app_user (id),
    constraint ux_app_review_user unique (user_id),
    constraint ck_app_review_rating check (rating between 1 and 5)
);

create index ix_app_review_written on app_review (updated_at);
