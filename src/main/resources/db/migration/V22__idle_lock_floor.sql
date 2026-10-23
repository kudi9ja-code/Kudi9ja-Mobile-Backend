-- The app locks after at least 10 minutes idle; raise anything saved below that.
update platform_settings set lock_timeout_minutes = 10 where lock_timeout_minutes < 10;
