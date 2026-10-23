-- Help and complaints: a conversation between a customer and the Kudi9ja team.
--
-- A complaint carries the clock the Terms promise — acknowledged within the
-- hours, resolved within the days set under kudi9ja.compliance — written on
-- the row when it is opened, so a later change to the policy does not move a
-- deadline somebody was already given.

create table support_conversation (
    id                     uuid          not null,
    user_id                uuid          not null,
    customer_name          varchar(160)  not null,
    customer_ref           varchar(40)   not null,
    reference              varchar(20)   not null,
    kind                   varchar(16)   not null,
    category               varchar(40)   not null,
    subject                varchar(160)  not null,
    status                 varchar(24)   not null,
    assigned_to_id         uuid,
    assigned_to_name       varchar(160),
    last_message_at        timestamptz   not null,
    last_message_preview   varchar(200),
    last_message_by        varchar(16),
    customer_unread        integer       not null default 0,
    staff_unread           integer       not null default 0,
    acknowledge_by         timestamptz,
    acknowledged_at        timestamptz,
    resolve_by             timestamptz,
    resolved_at            timestamptz,
    resolved_by            varchar(200),
    resolution_note        varchar(1000),
    created_at             timestamptz   not null,

    constraint pk_support_conversation primary key (id),
    constraint fk_support_conversation_user foreign key (user_id) references app_user (id),
    constraint ux_support_conversation_reference unique (reference),
    constraint ck_support_kind check (kind in ('COMPLAINT', 'ENQUIRY')),
    constraint ck_support_status check (status in ('OPEN', 'AWAITING_CUSTOMER', 'RESOLVED'))
);

create index ix_support_conversation_user on support_conversation (user_id, last_message_at);
create index ix_support_conversation_queue on support_conversation (status, last_message_at);

create table support_message (
    id                  uuid           not null,
    conversation_id     uuid           not null,
    author              varchar(16)    not null,
    author_id           uuid,
    author_name         varchar(160)   not null,
    body                varchar(4000),
    attachment_key      varchar(400),
    attachment_name     varchar(200),
    attachment_type     varchar(120),
    attachment_size     bigint,
    created_at          timestamptz    not null,

    constraint pk_support_message primary key (id),
    constraint fk_support_message_conversation foreign key (conversation_id)
        references support_conversation (id) on delete cascade,
    constraint ck_support_message_author check (author in ('CUSTOMER', 'STAFF', 'SYSTEM'))
);

create index ix_support_message_conversation on support_message (conversation_id, created_at);

-- A reply from the team is a notification of its own kind, and one that
-- cannot be switched off: a complaint answered into silence is not answered.
alter table notification drop constraint ck_notification_kind;
alter table notification add constraint ck_notification_kind check (kind in (
    'INTEREST', 'MATURITY', 'REPAYMENT_DUE', 'REPAYMENT_PAID',
    'AUTO_SAVE', 'THRIFT', 'SECURITY', 'GENERAL', 'SUPPORT'));

alter table notification_preference drop constraint ck_pref_kind;
alter table notification_preference add constraint ck_pref_kind check (kind in (
    'INTEREST', 'MATURITY', 'REPAYMENT_DUE', 'REPAYMENT_PAID',
    'AUTO_SAVE', 'THRIFT', 'SECURITY', 'GENERAL', 'SUPPORT'));

alter table notification_preference drop constraint ck_pref_not_silenceable;
alter table notification_preference add constraint ck_pref_not_silenceable
    check (enabled or kind not in ('SECURITY', 'GENERAL', 'SUPPORT'));
