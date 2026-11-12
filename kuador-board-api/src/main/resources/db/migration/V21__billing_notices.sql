-- Avisos de vencimiento y recibos de pago.

-- Configuración de la plataforma para cobros (una sola fila): días antes del vencimiento en que se avisa.
create table billing_settings (
    id             boolean     primary key default true check (id),
    reminder_days  integer[]   not null default '{7,3,1}',
    updated_at     timestamptz not null default now()
);
insert into billing_settings (id) values (true);

-- Avisos ya enviados, para no repetirlos: uno por espacio, tipo, fecha de vencimiento y días de anticipación.
create table billing_notice (
    workspace_id uuid        not null references workspace (id) on delete cascade,
    kind         varchar(20) not null check (kind in ('EXPIRING', 'EXPIRED')),
    due_at       timestamptz not null,
    days_before  integer     not null,
    sent_at      timestamptz not null,
    primary key (workspace_id, kind, due_at, days_before)
);

-- Quién inició el pago (recibe el recibo).
alter table payment add column requested_by uuid;
