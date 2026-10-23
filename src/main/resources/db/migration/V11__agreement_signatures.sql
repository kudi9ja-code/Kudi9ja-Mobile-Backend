-- The signature a customer draws when they accept their agreements.
--
-- legal_acceptance already records which version of each document was
-- accepted, when, and on what device. This adds what the customer signed with:
-- the name they typed and the signature they drew, so the record of an
-- electronic signature under section 93 of the Evidence Act 2011 holds the
-- signature itself and not only the fact of a tick.
--
-- The image is a key into object storage, like a receipt or a statement, and
-- is served to admins over a signed, expiring URL. Kept for as long as the
-- account and five years after: it is evidence of the agreements that governed it.

create table agreement_signature (
    id            uuid          not null,
    user_id       uuid          not null,
    typed_name    varchar(160)  not null,
    storage_key   varchar(300)  not null,
    content_type  varchar(60)   not null,
    size_bytes    bigint        not null,
    -- What was signed, as "terms 1.1, privacy 1.1, lending 1.0".
    documents     varchar(300)  not null,
    signed_at     timestamp(6) with time zone not null,
    device        varchar(200),
    ip_address    varchar(45),

    constraint pk_agreement_signature primary key (id),
    constraint fk_agreement_signature_user foreign key (user_id) references app_user (id)
);

create index ix_agreement_signature_user on agreement_signature (user_id, signed_at);
