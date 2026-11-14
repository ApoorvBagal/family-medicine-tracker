-- M0: one row per application start.
-- Purpose: prove that data survives restarts and redeploys (the container filesystem does not).
create table app_boot_log (
    id          bigserial    primary key,
    booted_at   timestamptz  not null default now(),
    app_version text         not null,
    host        text         not null
);
