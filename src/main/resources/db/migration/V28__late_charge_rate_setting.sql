-- The late charge per day, as a fraction of the amount borrowed: 0.0018 for
-- 0.18%. Set in Controls; the daily job always uses the current value.
alter table platform_settings add column late_charge_daily_rate numeric(12, 6) not null default 0.0018;
alter table platform_settings add constraint ck_settings_late_charge_daily_rate
    check (late_charge_daily_rate >= 0 and late_charge_daily_rate <= 0.05);
