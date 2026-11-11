-- Avisos a personas por correo y Telegram: preferencias de cada persona, vínculo con Telegram y la cola de envíos.

create table notification_settings (
    member_id        uuid        primary key,
    workspace_id     uuid        not null references workspace (id) on delete cascade,
    email            boolean     not null default true,
    telegram         boolean     not null default true,
    telegram_chat_id bigint,
    on_assigned      boolean     not null default true,
    on_gates         boolean     not null default true,
    on_plans         boolean     not null default true,
    updated_at       timestamptz not null
);

-- Código de un solo uso para vincular el chat de Telegram de una persona (se guarda el hash).
create table telegram_link (
    code_hash    varchar(64) primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    member_id    uuid        not null,
    expires_at   timestamptz not null
);

-- Cola de envíos: se reintenta con espera creciente; el destino queda resuelto al encolar.
create table notification (
    id              uuid         primary key,
    workspace_id    uuid         not null references workspace (id) on delete cascade,
    member_id       uuid         not null,
    channel         varchar(10)  not null check (channel in ('EMAIL', 'TELEGRAM')),
    address         varchar(320) not null,
    subject         varchar(300) not null,
    body            text         not null,
    link            varchar(500),
    status          varchar(10)  not null check (status in ('PENDING', 'SENT', 'FAILED')),
    attempts        integer      not null default 0,
    next_attempt_at timestamptz  not null,
    created_at      timestamptz  not null,
    sent_at         timestamptz,
    error           varchar(500)
);
create index notification_pending_idx on notification (next_attempt_at) where status = 'PENDING';
