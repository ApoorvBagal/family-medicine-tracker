-- M5: low-stock alerts by email (D11, D12, DESIGN §4 "Runway" and "Why an outbox").

-- Family members who get alert emails (they don't log in). The owner always gets them too.
create table alert_recipient (
    id                      uuid         primary key default gen_random_uuid(),
    household_id            uuid         not null references household (id) on delete restrict,
    name                    text         not null check (length(trim(name)) between 1 and 100),
    email                   text         not null check (email = lower(trim(email)) and length(email) between 3 and 254),
    -- Random 256-bit token in each email's unsubscribe link. Stored as is (not hashed): the link has to be
    -- rebuilt for every email, and the queued emails contain it anyway. A leak only allows unsubscribing.
    unsubscribe_token       text         not null unique,
    active                  boolean      not null default true,
    unsubscribed_at         timestamptz,
    created_at              timestamptz  not null default now(),
    unique (household_id, email)
);

-- Last known alert status of each medicine, so an email goes out only when the status changes.
create table medicine_alert_state (
    medicine_id        uuid         primary key references medicine (id) on delete restrict,
    household_id       uuid         not null references household (id) on delete restrict,
    status             text         not null check (status in ('OK', 'LOW', 'OUT')),
    changed_at         timestamptz  not null,
    -- The last LOW/OUT email (for the 6-hour repeat guard) and whether a "restocked" email is owed.
    last_alert_status  text         check (last_alert_status in ('LOW', 'OUT')),
    last_alert_at      timestamptz,
    restock_pending    boolean      not null default false
);

-- Emails to send. Written in the same transaction as the stock change, sent after commit,
-- retried by the daily job. One row per recipient.
create table notification_outbox (
    id               bigserial    primary key,
    household_id     uuid         not null references household (id) on delete restrict,
    to_email         text         not null,
    to_name          text,
    subject          text         not null,
    body_text        text         not null,
    unsubscribe_url  text,
    created_at       timestamptz  not null default now(),
    attempts         int          not null default 0,
    next_attempt_at  timestamptz  not null default now(),
    sent_at          timestamptz,
    last_error       text
);
create index notification_outbox_pending_idx on notification_outbox (next_attempt_at) where sent_at is null;

-- Each run of the daily job (GitHub Actions cron), shown in the app as "last daily check".
create table job_run (
    id           bigserial    primary key,
    name         text         not null,
    started_at   timestamptz  not null,
    finished_at  timestamptz,
    summary      jsonb        not null default '{}'::jsonb
);
create index job_run_name_idx on job_run (name, started_at desc);
