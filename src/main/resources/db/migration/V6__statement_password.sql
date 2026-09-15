-- The password on a locked bank statement.
--
-- Banks send statements as PDFs locked with the customer's date of birth or
-- phone number. The file is stored as sent, lock and all, and an admin could
-- not open one without going back to the customer to ask. The customer knows
-- the password when they attach the file, so the form asks then.
--
-- Nullable: most images and spreadsheets have no password, and an application
-- from before this column existed has nothing to say here.

alter table loan_application
    add column statement_password varchar(64);
