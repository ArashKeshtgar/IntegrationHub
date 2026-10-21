-- The external lab's own test codes against ClinicHub's LOINC (docs/architecture.md, phase 8). An order goes out with
-- the lab's code first and the LOINC as the alternate; a result coded the lab's way comes back with the LOINC added.
create table hl7_code_map (
    loinc       varchar(20)  primary key,
    lab_code    varchar(40)  not null unique,
    lab_display varchar(200),
    updated_at  timestamptz  not null default now()
);
