-- Inicio de sesión de los clientes de IA en el servidor MCP (OAuth 2.1 con PKCE). Tablas globales:
-- un cliente (Claude Code, Codex...) se registra solo, sin espacio; el código ya lleva el espacio de la persona.

create table oauth_client (
    client_id     varchar(64)  primary key,
    name          varchar(100) not null,
    redirect_uris text         not null, -- una por línea
    created_at    timestamptz  not null
);

-- Códigos de autorización: de un solo uso y de vida corta. Se guarda el hash, nunca el código.
create table oauth_code (
    code_hash      varchar(64)  primary key,
    client_id      varchar(64)  not null references oauth_client (client_id) on delete cascade,
    workspace_id   uuid         not null references workspace (id) on delete cascade,
    member_id      uuid         not null,
    redirect_uri   text         not null,
    code_challenge varchar(128) not null,
    expires_at     timestamptz  not null,
    used_at        timestamptz
);
create index oauth_code_expires_idx on oauth_code (expires_at);
