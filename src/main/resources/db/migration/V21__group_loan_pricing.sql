-- Group loans get their own pricing.
--
-- Everything that prices a loan — the rate for each month, the longest term,
-- the smallest and largest loan, the management fee, the transaction fee on
-- each repayment — now exists twice: once for everyone, once for members of a
-- loan group. The group set starts as an exact copy of today's, so nothing
-- changes until somebody edits it. Each set also says whether weekly and
-- monthly repayment are offered.

alter table platform_settings add column loan_weekly_allowed boolean not null default true;
alter table platform_settings add column loan_monthly_allowed boolean not null default true;

alter table platform_settings add column group_max_loan_tenure_months integer;
alter table platform_settings add column group_min_loan_amount numeric(19, 2);
alter table platform_settings add column group_max_loan_amount numeric(19, 2);
alter table platform_settings add column group_flat_processing_fee numeric(19, 2);
alter table platform_settings add column group_processing_fee_threshold numeric(19, 2);
alter table platform_settings add column group_loan_processing_fee_rate numeric(12, 6);
alter table platform_settings add column group_loan_repayment_fee_rate numeric(12, 6);
alter table platform_settings add column group_loan_weekly_allowed boolean not null default true;
alter table platform_settings add column group_loan_monthly_allowed boolean not null default true;

update platform_settings
   set group_max_loan_tenure_months = max_loan_tenure_months,
       group_min_loan_amount = min_loan_amount,
       group_max_loan_amount = max_loan_amount,
       group_flat_processing_fee = flat_processing_fee,
       group_processing_fee_threshold = processing_fee_threshold,
       group_loan_processing_fee_rate = loan_processing_fee_rate,
       group_loan_repayment_fee_rate = loan_repayment_fee_rate;

alter table platform_settings alter column group_max_loan_tenure_months set not null;
alter table platform_settings alter column group_min_loan_amount set not null;
alter table platform_settings alter column group_max_loan_amount set not null;
alter table platform_settings alter column group_flat_processing_fee set not null;
alter table platform_settings alter column group_processing_fee_threshold set not null;
alter table platform_settings alter column group_loan_processing_fee_rate set not null;
alter table platform_settings alter column group_loan_repayment_fee_rate set not null;

alter table platform_settings add constraint ck_settings_group_repayment_fee_rate
    check (group_loan_repayment_fee_rate >= 0 and group_loan_repayment_fee_rate <= 0.2);
alter table platform_settings add constraint ck_settings_repayment_choice
    check (loan_weekly_allowed or loan_monthly_allowed);
alter table platform_settings add constraint ck_settings_group_repayment_choice
    check (group_loan_weekly_allowed or group_loan_monthly_allowed);

-- The group rate card, copied from today's for every settings version.
create table platform_group_loan_rate (
    settings_version bigint         not null,
    tenure_months    integer        not null,
    flat_rate        numeric(12, 6) not null,
    constraint pk_platform_group_loan_rate primary key (settings_version, tenure_months),
    constraint fk_group_loan_rate_settings foreign key (settings_version)
        references platform_settings (version) on delete cascade,
    constraint ck_group_loan_rate_tenure check (tenure_months >= 1),
    constraint ck_group_loan_rate_positive check (flat_rate > 0)
);

insert into platform_group_loan_rate (settings_version, tenure_months, flat_rate)
select settings_version, tenure_months, flat_rate from platform_loan_rate;
