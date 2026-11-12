-- Agentes por tablero, en el servidor o en la PC de una persona, y la clave de IA de cada espacio.

-- Cómo trabaja cada agente: LOCAL = desde el cliente de IA de una persona (MCP, /pendientes);
-- SERVER = en el tablero, con la clave de IA del espacio. Sin fila: LOCAL y el modelo por defecto.
create table agent_profile (
    member_id    uuid        primary key references member (id) on delete cascade,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    mode         varchar(10) not null default 'LOCAL' check (mode in ('LOCAL', 'SERVER')),
    model        varchar(60),
    updated_at   timestamptz not null
);

-- Equipo de agentes de cada tablero.
create table project_agent (
    workspace_id uuid        not null,
    project_key  varchar(10) not null,
    member_id    uuid        not null references member (id) on delete cascade,
    added_at     timestamptz not null default now(),
    primary key (workspace_id, project_key, member_id),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

-- Los tableros que ya existen siguen con todos los agentes del espacio.
insert into project_agent (workspace_id, project_key, member_id)
select p.workspace_id, p.project_key, m.id from project p join member m on m.workspace_id = p.workspace_id and m.kind = 'AGENT';

-- Clave de IA de la empresa (cifrada) para los agentes que trabajan en el tablero.
create table workspace_ai (
    workspace_id  uuid        primary key references workspace (id) on delete cascade,
    provider      varchar(20) not null default 'ANTHROPIC',
    api_key_enc   text        not null,
    key_hint      varchar(12) not null,
    default_model varchar(60) not null default 'claude-opus-5-5',
    updated_at    timestamptz not null
);
