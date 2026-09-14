-- A photograph of the applicant, on every application.
--
-- The account was opened against a BVN, but a BVN is a number and a loan is
-- money handed to a person. This is the face the admin is lending to, and the
-- face a guarantor will be asked about.
--
-- Not null, so an application cannot be written without one — but the column
-- is added nullable first and then tightened, because a table that already
-- holds rows cannot take a NOT NULL column with no default. Rows from before
-- this migration get a marker key rather than a fabricated file: it says
-- plainly that nothing was uploaded, and the admin endpoint refuses it the same
-- way it refuses any key that is not in storage.

alter table loan_application
    add column selfie_key           varchar(300),
    add column selfie_content_type  varchar(120),
    add column selfie_size_bytes    bigint;

update loan_application
   set selfie_key = 'none/before-selfies-were-required'
 where selfie_key is null;

alter table loan_application
    alter column selfie_key set not null;
