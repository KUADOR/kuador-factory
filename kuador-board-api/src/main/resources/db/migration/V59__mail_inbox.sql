-- Bandeja de correo de un tablero: cada correo nuevo de un buzón conectado (Outlook, Gmail o correo con contraseña) se
-- vuelve una tarjeta, con su texto y sus adjuntos. «since» es hasta dónde se revisó (la hora del último correo visto).
create table mail_inbox (
    id               uuid primary key,
    workspace_id     uuid         not null references workspace (id) on delete cascade,
    project_key      varchar(10)  not null,
    conn_key         varchar(24)  not null,
    folder           varchar(100),
    from_filter      varchar(200),
    only_attachments boolean      not null default false,
    status           varchar(30),
    assignee_id      uuid references member (id) on delete set null,
    enabled          boolean      not null default true,
    created_by       uuid references member (id) on delete set null,
    since            timestamptz  not null,
    last_checked_at  timestamptz,
    last_error       text,
    last_issue_key   varchar(20),
    cards_created    int          not null default 0,
    created_at       timestamptz  not null,
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade,
    foreign key (workspace_id, conn_key) references business_connection (workspace_id, conn_key) on delete cascade
);
create index mail_inbox_project_idx on mail_inbox (workspace_id, project_key);

-- Los correos ya vistos por cada bandeja (por su Message-ID o, si no tiene, por el id del proveedor): no se repiten.
create table mail_inbox_seen (
    inbox_id  uuid         not null references mail_inbox (id) on delete cascade,
    mail_key  varchar(500) not null,
    issue_key varchar(20),
    seen_at   timestamptz  not null,
    primary key (inbox_id, mail_key)
);
