-- Where a guarantor's address actually is.
--
-- A street address in Nigeria is often not enough on its own: numbering is
-- irregular and streets share names across a city. State and local government
-- narrow it to a place; a landmark is how anybody finds it. If a loan ever has
-- to be pursued, these three are what a visit is planned from.
--
-- Added nullable, back-filled for guarantors recorded before they were asked,
-- then tightened — a table with rows cannot take a NOT NULL column directly.
-- The back-fill is a plain marker rather than a guess: nobody was asked, and
-- the row should say so.

alter table loan_application_guarantor
    add column state             varchar(60),
    add column local_government  varchar(120),
    add column landmark          varchar(200);

update loan_application_guarantor
   set state            = coalesce(state, 'Not recorded'),
       local_government = coalesce(local_government, 'Not recorded'),
       landmark         = coalesce(landmark, 'Not recorded');

alter table loan_application_guarantor
    alter column state            set not null,
    alter column local_government set not null,
    alter column landmark         set not null;
