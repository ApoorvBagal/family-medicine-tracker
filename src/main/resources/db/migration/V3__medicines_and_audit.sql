-- M2: medicines (no stock quantities yet; stock lives in batches/ledger from M3) and a light audit log.

create table medicine (
    id                uuid           primary key default gen_random_uuid(),
    household_id      uuid           not null references household (id) on delete restrict,
    name              text           not null check (length(trim(name)) between 1 and 100),
    brand_name        text           check (brand_name is null or length(brand_name) <= 100),
    strength          text           check (strength is null or length(strength) <= 50),
    dosage_form       text           not null check (dosage_form in
                          ('TABLET', 'CAPSULE', 'SYRUP', 'DROPS', 'INHALER', 'CREAM', 'INJECTION', 'OTHER')),
    unit              text           not null check (unit in
                          ('TABLET', 'CAPSULE', 'ML', 'BOTTLE', 'STRIP', 'TUBE', 'PIECE', 'OTHER')),
    min_threshold     numeric(10, 2) not null default 0 check (min_threshold >= 0),
    -- D11: regular medicines get a daily dose; low-stock warning when estimated days left <= warn_days.
    daily_dose        numeric(10, 2) check (daily_dose is null or daily_dose > 0),
    warn_days         int            not null default 5 check (warn_days between 0 and 60),
    storage_location  text           check (storage_location is null or length(storage_location) <= 100),
    notes             text           check (notes is null or length(notes) <= 1000),
    archived_at       timestamptz,
    version           bigint         not null default 0,   -- optimistic locking (ETag / If-Match)
    created_by        uuid           not null references app_user (id),
    created_at        timestamptz    not null default now(),
    updated_at        timestamptz    not null default now()
);

-- Duplicate guard among active medicines: same name + strength + form, ignoring case and spaces
-- ("Paracetamol 500mg tablet" == "paracetamol  500 MG tablet"). Archived ones don't count.
create unique index medicine_active_dedup_uq on medicine (
    household_id,
    lower(regexp_replace(name, '\s+', '', 'g')),
    lower(regexp_replace(coalesce(strength, ''), '\s+', '', 'g')),
    dosage_form
) where archived_at is null;

create index medicine_household_idx on medicine (household_id, archived_at);

-- Who changed what (non-stock changes; stock gets its own ledger in M3). Only changed fields are stored.
create table audit_event (
    id              bigserial    primary key,
    household_id    uuid         not null references household (id) on delete restrict,
    actor_user_id   uuid         not null references app_user (id),
    entity_type     text         not null,
    entity_id       uuid         not null,
    action          text         not null,
    changes         jsonb        not null default '{}'::jsonb,
    occurred_at     timestamptz  not null default now()
);

create index audit_event_entity_idx on audit_event (household_id, entity_type, entity_id, occurred_at desc);
