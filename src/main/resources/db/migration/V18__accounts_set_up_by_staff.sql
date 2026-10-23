-- Customers set up by the team.
--
-- When an admin enters a loan customer, a real account is opened for them at
-- once — customer number, wallet, the loan on it — marked PENDING_ACTIVATION.
-- It cannot be signed in to: the customer activates it by signing up with the
-- BVN the admin entered, which hands them this same account.
--
-- Email may be blank until then: the admin may not have one, and the customer
-- gives it when they sign up. Unique still holds for every email that is set.

alter table app_user alter column email drop not null;

alter table app_user drop constraint ck_user_status;
alter table app_user add constraint ck_user_status
    check (account_status in ('ACTIVE', 'FLAGGED', 'FROZEN', 'DORMANT', 'CLOSED', 'PENDING_ACTIVATION'));
