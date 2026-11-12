-- Pasarelas de pago configurables (las edita quien administra la plataforma) y los pagos de los espacios.
-- Cada pasarela usa un adaptador por tipo (PAYPAL, …); sus credenciales se guardan cifradas como JSON.

create table payment_provider (
    code            varchar(30)  primary key check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    type            varchar(30)  not null,
    name            varchar(60)  not null,
    mode            varchar(10)  not null check (mode in ('SANDBOX', 'LIVE')),
    credentials_enc text         not null,
    active          boolean      not null default false,
    sort_order      integer      not null default 0,
    updated_at      timestamptz  not null
);

create table payment (
    id            uuid          primary key,
    workspace_id  uuid          not null references workspace (id) on delete cascade,
    plan_code     varchar(30)   not null references plan (code),
    provider_code varchar(30)   not null references payment_provider (code),
    amount        numeric(10, 2) not null,
    currency      varchar(3)    not null,
    months        integer       not null check (months between 1 and 24),
    status        varchar(10)   not null check (status in ('CREATED', 'PAID', 'FAILED')),
    external_id   varchar(100),
    created_at    timestamptz   not null,
    paid_at       timestamptz
);
create index payment_workspace_idx on payment (workspace_id, created_at desc);
create unique index payment_external_idx on payment (provider_code, external_id);
