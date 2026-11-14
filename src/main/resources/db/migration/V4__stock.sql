-- M3: stock in batches ("lots", D1) and an append-only ledger of every change.

-- A batch of one medicine, e.g. one purchased strip/bottle lot with its own expiry date.
create table stock_lot (
    id            uuid           primary key default gen_random_uuid(),
    household_id  uuid           not null references household (id) on delete restrict,
    medicine_id   uuid           not null references medicine (id) on delete restrict,
    expiry_date   date,                                   -- null = no expiry recorded (counted as usable)
    quantity      numeric(10, 2) not null check (quantity >= 0),   -- last line of defence: never negative
    label         text           check (label is null or length(label) <= 100),
    created_at    timestamptz    not null default now()
);
create index stock_lot_medicine_idx on stock_lot (medicine_id, expiry_date);

-- One user action (header). Never updated or deleted; mistakes are fixed with a REVERSAL movement.
create table stock_movement (
    id                    uuid           primary key default gen_random_uuid(),
    household_id          uuid           not null references household (id) on delete restrict,
    medicine_id           uuid           not null references medicine (id) on delete restrict,
    type                  text           not null check (type in
                              ('PURCHASE', 'CONSUMPTION', 'CORRECTION', 'DISPOSAL', 'REVERSAL')),
    note                  text           check (note is null or length(note) <= 500),
    performed_by          uuid           not null references app_user (id),
    occurred_at           timestamptz    not null,          -- when it happened (user may back-date)
    recorded_at           timestamptz    not null default now(),
    usable_after          numeric(10, 2) not null,          -- usable stock right after this movement
    reverses_movement_id  uuid           unique references stock_movement (id),  -- at most one undo each
    idempotency_key       text           not null check (length(idempotency_key) between 1 and 100),
    unique (household_id, idempotency_key)                  -- a retried request is applied only once
);
create index stock_movement_medicine_idx on stock_movement (medicine_id, recorded_at desc);

-- How a movement changed each batch (a dose can take from two batches).
create table stock_movement_line (
    id                  bigserial      primary key,
    movement_id         uuid           not null references stock_movement (id) on delete restrict,
    lot_id              uuid           not null references stock_lot (id) on delete restrict,
    delta               numeric(10, 2) not null,
    lot_quantity_after  numeric(10, 2) not null check (lot_quantity_after >= 0)
);
create index stock_movement_line_movement_idx on stock_movement_line (movement_id);
