-- Face check at sign-up.
--
-- After BVN and NIN the customer blinks, turns their head and opens their
-- mouth on camera; one photo of each is kept here. It is not identity
-- verification: the photos are kept so the team can see who opened the
-- account, on the customer's record in the admin panel.
--
-- Photos are taken before the account exists, so they belong to the sign-up
-- draft first (draft_id) and to the customer (user_id) once the account opens.
-- Photos of a sign-up that was never finished are deleted with the draft.

alter table signup_draft drop constraint ck_draft_step;
alter table signup_draft add constraint ck_draft_step check (step in (
    'PERSONAL', 'EMAIL_VERIFIED', 'IDENTITY', 'LIVENESS', 'PAYOUT',
    'PASSWORD', 'PASSCODE', 'PIN', 'COMPLETE'));

create table liveness_photo (
    id            uuid          not null,
    draft_id      uuid          not null,
    user_id       uuid,
    pose          varchar(16)   not null,
    storage_key   varchar(400)  not null,
    content_type  varchar(60)   not null,
    size_bytes    integer       not null,
    captured_at   timestamptz   not null,

    constraint pk_liveness_photo primary key (id),
    constraint fk_liveness_photo_user foreign key (user_id) references app_user (id),
    constraint ck_liveness_pose check (pose in ('BLINK', 'TURN', 'MOUTH'))
);

create index ix_liveness_draft on liveness_photo (draft_id);
create index ix_liveness_user on liveness_photo (user_id);
