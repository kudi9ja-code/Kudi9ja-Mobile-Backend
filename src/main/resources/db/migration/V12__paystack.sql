-- Paystack: money in by checkout, money out by transfer.
--
-- Payments in no longer wait for an admin to match a receipt against a bank
-- statement. The customer pays at Paystack's checkout — card, bank transfer or
-- USSD — and the wallet is credited the moment Paystack confirms it, at most
-- once, however many times the confirmation arrives.
--
-- Payments out still wait for an admin to approve them. Approval now sends the
-- money through a Paystack transfer, and the withdrawal records how that went:
-- the debit from the wallet stays pending until Paystack says the transfer
-- succeeded, and a failed or returned transfer is refunded in full.
--
-- The receipt-claim tables stay: they hold five years of evidence, and an
-- admin can still match an unmatched credit by hand.

create table paystack_payment (
    id                 uuid           not null,
    user_id            uuid           not null,
    customer_name      varchar(160)   not null,
    customer_ref       varchar(16)    not null,
    reference          varchar(64)    not null,
    purpose            varchar(20)    not null,
    loan_id            uuid,
    amount             numeric(19, 2) not null,
    status             varchar(16)    not null,
    authorization_url  varchar(500),
    channel            varchar(40),
    card_type          varchar(40),
    card_last4         varchar(4),
    card_bank          varchar(120),
    fees               numeric(19, 2),
    gateway_response   varchar(300),
    created_at         timestamp(6) with time zone not null,
    paid_at            timestamp(6) with time zone,
    credited_at        timestamp(6) with time zone,
    row_version        bigint         not null,

    constraint pk_paystack_payment primary key (id),
    constraint uk_paystack_payment_reference unique (reference),
    constraint fk_paystack_payment_user foreign key (user_id) references app_user (id),
    constraint fk_paystack_payment_loan foreign key (loan_id) references loan (id),
    constraint ck_paystack_payment_amount check (amount > 0),
    constraint ck_paystack_payment_status
        check (status in ('INITIALIZED', 'SUCCESSFUL', 'FAILED', 'ABANDONED')),
    constraint ck_paystack_payment_purpose check (purpose in ('WALLET', 'LOAN_REPAYMENT'))
);

create index ix_paystack_payment_user on paystack_payment (user_id, created_at);
create index ix_paystack_payment_status on paystack_payment (status, created_at);

alter table withdrawal_request
    -- MANUAL when an admin paid it by hand; PAYSTACK when approval sent it.
    add column payout_provider     varchar(20),
    -- PROCESSING until Paystack reports the outcome, then PAID or FAILED.
    add column payout_status       varchar(16),
    add column transfer_reference  varchar(64),
    add column transfer_code       varchar(64),
    add column payout_failure      varchar(500),
    add column paid_out_at         timestamp(6) with time zone;

create index ix_withdrawal_transfer_reference on withdrawal_request (transfer_reference);
