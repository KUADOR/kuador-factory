-- Conexiones del negocio: los sistemas de la empresa (correo, agenda, ERP) que los agentes usan a través del tablero.
-- Cada una es un servidor MCP por HTTP; la credencial va cifrada con BOARD_SECRETS_KEY y nunca se devuelve.
create table business_connection (
    workspace_id   uuid         not null references workspace (id) on delete cascade,
    conn_key       varchar(24)  not null,
    name           varchar(80)  not null,
    url            varchar(500) not null,
    header         varchar(64),           -- cabecera de la credencial; nulo = Authorization: Bearer …
    secret         text,                  -- credencial cifrada; nulo = el servidor no pide
    tools          text         not null default '[]', -- JSON: lo que el servidor ofreció la última vez [{name, description, schema, readOnly}]
    allowed_writes text         not null default '[]', -- JSON: herramientas que escriben y que se habilitaron para los agentes
    checked_at     timestamptz,
    primary key (workspace_id, conn_key)
);

-- En qué tableros se usa cada conexión y para qué roles ('[]' = todos los del tablero).
create table business_connection_board (
    workspace_id uuid        not null,
    conn_key     varchar(24) not null,
    project_key  varchar(10) not null,
    roles        text        not null default '[]',
    primary key (workspace_id, conn_key, project_key),
    foreign key (workspace_id, conn_key) references business_connection (workspace_id, conn_key) on delete cascade
);
create index business_connection_board_project on business_connection_board (workspace_id, project_key);
