-- One-off: removes every loan of customer K9-227905 (Ayinde Abiodun), at the
-- owner's request on 2026-10-29.
--
-- Goes: his loan applications (their photos and guarantors cascade), both
-- loans, the 8 wallet lines they funded (two payouts, two management fees,
-- three withdrawals and the refund of the declined one), the three
-- withdrawal records, and the notifications about loans and withdrawals. The
-- wallet is left at zero. Nothing else on the account is touched; the audit
-- log keeps its history and gains a line saying this was done.
--
-- Refuses, changing nothing, if the account holds anything other than what
-- was reviewed: a different number of wallet lines, a kind not listed above,
-- or a loan that was entered by hand rather than lent through the app. A
-- database without this customer (a fresh or test one) is left alone.

do $$
declare
    uid      uuid;
    wid      uuid;
    n_tx     int;
    n_other  int;
begin
    select id into uid from app_user where customer_ref = 'K9-227905';
    if uid is null then
        return;
    end if;
    select id into wid from wallet where user_id = uid;

    select count(*) into n_tx from wallet_transaction where user_id = uid;
    select count(*) into n_other from wallet_transaction
        where user_id = uid
          and kind not in ('LOAN_DISBURSEMENT', 'FEE', 'WITHDRAWAL', 'DEPOSIT');
    if n_tx <> 8 or n_other <> 0 then
        raise exception 'K9-227905 has % wallet lines (% of other kinds), not the 8 reviewed. Nothing removed.', n_tx, n_other;
    end if;
    if exists (select 1 from wallet_transaction
               where user_id = uid and kind = 'DEPOSIT'
                 and coalesce(related_type, '') <> 'withdrawal' and description not like 'Refund%') then
        raise exception 'K9-227905 has a deposit that is not a withdrawal refund. Nothing removed.';
    end if;
    if exists (select 1 from imported_loan where user_id = uid or loan_id in (select id from loan where user_id = uid)) then
        raise exception 'K9-227905 has a loan entered by hand. Nothing removed.';
    end if;
    if exists (select 1 from thrift_contribution where transaction_id in (select id from wallet_transaction where user_id = uid)) then
        raise exception 'K9-227905 has a wallet line used by a thrift circle. Nothing removed.';
    end if;

    delete from loan_application where user_id = uid;
    delete from loan_document      where loan_id in (select id from loan where user_id = uid);
    delete from recorded_repayment where loan_id in (select id from loan where user_id = uid);
    delete from paystack_payment   where loan_id in (select id from loan where user_id = uid);
    delete from withdrawal_request where user_id = uid;
    delete from wallet_transaction where user_id = uid;
    delete from loan               where user_id = uid;

    update wallet set balance = 0, updated_at = now() where id = wid;

    delete from notification
        where user_id = uid
          and (title ilike '%loan%'
               or title ilike '%withdrawal%'
               or title ilike '%repayment%'
               or title like 'Instalment %'
               or title = 'Application received');

    insert into audit_entry (id, actor, category, action, detail, subject_id, subject_label, occurred_at)
    values (gen_random_uuid(), 'System', 'CUSTOMER', 'Loan history removed',
            'At the owner''s request: both loans, the loan applications, the 8 wallet lines they funded, '
                || 'the 3 withdrawals and the loan and withdrawal notifications were removed. Wallet set to ₦0.',
            uid, 'K9-227905', now());
end $$;
