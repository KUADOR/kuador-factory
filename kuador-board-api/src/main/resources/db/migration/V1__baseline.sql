-- Línea base del tablero KUADOR.
--
-- Multi-empresa: todo dato de negocio pertenece a un espacio de trabajo (workspace_id) y las claves
-- de negocio (KUA, KUA-8) son únicas por espacio, no globales. Las relaciones entre tablas incluyen
-- el espacio, así un registro nunca puede apuntar a otro espacio. La aplicación filtra cada consulta
-- por el espacio de quien pregunta (Hibernate @TenantId).
--
-- Tablas globales (sin filtro por espacio): workspace, credential, access_token (se buscan antes de
-- saber el espacio) y el registro de eventos de Spring Modulith.

create table workspace (
    id         uuid primary key,
    slug       varchar(40)  not null unique,
    name       varchar(120) not null,
    created_at timestamptz  not null
);

-- ---------------------------------------------------------------- Catálogo (configurable por espacio)

-- Una tabla para los cuatro catálogos (plantillas de flujo, metodologías, roles de agente,
-- servidores MCP): el tipo y la clave identifican la entrada y la definición viaja en JSON. Cada tipo
-- tiene sus reglas en el código; la persistencia, el versionado y "restaurar de fábrica" son comunes.
create table catalog_entry (
    id           uuid primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    type         varchar(20) not null check (type in ('WORKFLOW_TEMPLATE', 'METHODOLOGY', 'AGENT_ROLE', 'MCP_SERVER')),
    entry_key    varchar(40) not null,
    payload      text        not null,
    built_in     boolean     not null, -- viene de fábrica: se puede editar y restaurar
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      bigint      not null default 0,
    unique (workspace_id, type, entry_key)
);

-- ---------------------------------------------------------------- Equipo y acceso

create table member (
    id           uuid primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    name         varchar(120) not null,
    kind         varchar(10)  not null check (kind in ('HUMAN', 'AGENT')),
    role         varchar(40)  not null,
    description  text,
    avatar_color varchar(7)   not null,
    created_at   timestamptz  not null,
    unique (workspace_id, id)
);

create index member_workspace_idx on member (workspace_id, kind, name);

-- Global: el login ocurre antes de saber el espacio. Una persona pertenece a un espacio por ahora.
create table credential (
    member_id     uuid primary key references member (id) on delete cascade,
    email         varchar(254) not null unique,
    password_hash varchar(255) not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null
);

-- Global: se resuelve antes de saber el espacio; workspace_id dice a qué espacio da acceso.
create table access_token (
    id           uuid primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    member_id    uuid        not null references member (id) on delete cascade,
    kind         varchar(10) not null check (kind in ('SESSION', 'AGENT')),
    name         varchar(80) not null,
    token_hash   varchar(64) not null unique,
    created_at   timestamptz not null,
    expires_at   timestamptz,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

create index access_token_member_idx on access_token (member_id);

-- ---------------------------------------------------------------- Proyectos y tablero

create table project (
    id                uuid primary key,
    workspace_id      uuid         not null references workspace (id) on delete cascade,
    project_key       varchar(10)  not null,
    name              varchar(120) not null,
    description       text,
    kind              varchar(10)  not null check (kind in ('SOFTWARE', 'GENERAL')),
    template_key      varchar(40)  not null,
    methodology       varchar(40),
    issue_types       text         not null, -- JSON: [{type, label}] copiados de la plantilla
    next_issue_number integer      not null default 1,
    created_at        timestamptz  not null,
    unique (workspace_id, project_key),
    check (methodology is null or kind = 'SOFTWARE')
);

create table board_column (
    id           uuid primary key,
    workspace_id uuid        not null,
    project_key  varchar(10) not null,
    column_key   varchar(30) not null,
    name         varchar(40) not null,
    category     varchar(12) not null check (category in ('TODO', 'IN_PROGRESS', 'DONE')),
    position     integer     not null,
    unique (workspace_id, project_key, column_key),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

create table issue (
    id           uuid primary key,
    workspace_id uuid         not null,
    issue_key    varchar(20)  not null,
    project_key  varchar(10)  not null,
    title        varchar(255) not null,
    description  text,
    type         varchar(10)  not null check (type in ('EPIC', 'STORY', 'TASK', 'BUG')),
    status       varchar(30)  not null,
    priority     varchar(10)  not null check (priority in ('HIGHEST', 'HIGH', 'MEDIUM', 'LOW', 'LOWEST')),
    story_points integer check (story_points between 0 and 100),
    assignee_id  uuid,
    reporter_id  uuid,
    parent_key   varchar(20),
    labels       text[]       not null default '{}',
    position     integer      not null,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    version      bigint       not null default 0,
    unique (workspace_id, issue_key),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade,
    foreign key (workspace_id, parent_key) references issue (workspace_id, issue_key) on delete set null (parent_key)
);

create index issue_board_idx on issue (workspace_id, project_key, status, position);

create table issue_comment (
    id           uuid primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    issue_id     uuid        not null references issue (id) on delete cascade,
    author_id    uuid        not null,
    body         text        not null,
    created_at   timestamptz not null
);

create index issue_comment_issue_idx on issue_comment (issue_id, created_at);

-- ---------------------------------------------------------------- Historial

create table activity (
    id           uuid primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    event_id     uuid        not null,
    issue_key    varchar(20),
    project_key  varchar(10) not null,
    actor_id     uuid,
    type         varchar(12) not null check (type in ('CREATED', 'UPDATED', 'MOVED', 'ASSIGNED', 'COMMENTED', 'DELETED', 'DOCUMENT')),
    field        varchar(40),
    from_value   text,
    to_value     text,
    occurred_at  timestamptz not null
);

create index activity_issue_idx on activity (workspace_id, issue_key, occurred_at desc);
create index activity_project_idx on activity (workspace_id, project_key, occurred_at desc);
create index activity_event_idx on activity (event_id);

-- ---------------------------------------------------------------- Documentos

create table document (
    id              uuid primary key,
    workspace_id    uuid         not null,
    project_key     varchar(10)  not null,
    issue_key       varchar(20),
    kind            varchar(40)  not null, -- clave definida por la metodología (SPEC, CONSTITUTION...)
    title           varchar(200) not null,
    path            varchar(300) not null,
    current_version integer      not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    updated_by      uuid,
    version         bigint       not null default 0,
    unique (workspace_id, project_key, path),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

create unique index document_scope_kind_idx on document (workspace_id, project_key, coalesce(issue_key, ''), kind);
create index document_issue_idx on document (workspace_id, issue_key);

create table document_version (
    document_id  uuid        not null references document (id) on delete cascade,
    version      integer     not null,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    content      text        not null,
    author_id    uuid,
    note         varchar(200),
    created_at   timestamptz not null,
    primary key (document_id, version)
);

-- ---------------------------------------------------------------- Repositorio y sincronización

create table project_repository (
    id           uuid primary key,
    workspace_id uuid        not null,
    project_key  varchar(10) not null,
    kind         varchar(10) not null check (kind in ('LOCAL', 'GITHUB')),
    location     text        not null,
    branch       varchar(255),
    secret       text, -- token cifrado (AES-GCM); nunca sale de la API
    linked_at    timestamptz not null,
    linked_by    uuid,
    unique (workspace_id, project_key),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

create table document_sync (
    document_id    uuid primary key references document (id) on delete cascade,
    workspace_id   uuid        not null references workspace (id) on delete cascade,
    synced_version integer     not null,
    file_sha256    varchar(64) not null,
    synced_at      timestamptz not null
);

create table sync_pull_request (
    id           uuid primary key,
    workspace_id uuid         not null,
    project_key  varchar(10)  not null,
    number       integer      not null,
    url          varchar(500) not null,
    branch       varchar(255) not null,
    state        varchar(8)   not null check (state in ('OPEN', 'MERGED', 'CLOSED')),
    created_at   timestamptz  not null,
    created_by   uuid,
    unique (workspace_id, project_key, number),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

create table sync_pull_request_item (
    pull_request_id uuid        not null references sync_pull_request (id) on delete cascade,
    document_id     uuid        not null references document (id) on delete cascade,
    version         integer     not null,
    content_sha256  varchar(64) not null,
    primary key (pull_request_id, document_id)
);

-- ---------------------------------------------------------------- Agentes

create table agent_run (
    id               uuid primary key,
    workspace_id     uuid        not null references workspace (id) on delete cascade,
    agent_id         uuid        not null,
    issue_key        varchar(20) not null,
    project_key      varchar(10) not null,
    trigger_event_id uuid        not null unique,
    requested_by     uuid,
    status           varchar(10) not null check (status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    model            varchar(60),
    input_tokens     integer,
    output_tokens    integer,
    summary          text,
    error            text,
    created_at       timestamptz not null,
    finished_at      timestamptz
);

create index agent_run_agent_idx on agent_run (workspace_id, agent_id, created_at desc);
create index agent_run_issue_idx on agent_run (workspace_id, issue_key, created_at desc);

-- ---------------------------------------------------------------- Spring Modulith: registro de eventos
-- (esquema v2 de spring-modulith-events-jdbc 2.1.1; completion-mode=delete)

create table event_publication (
    id                     uuid                     not null primary key,
    listener_id            text                     not null,
    event_type             text                     not null,
    serialized_event       text                     not null,
    publication_date       timestamp with time zone not null,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone
);

create index event_publication_serialized_event_hash_idx on event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx on event_publication (completion_date);

-- Hibernate valida esta tabla aunque no se use el modo archive.
create table event_publication_archive (
    id                     uuid                     not null primary key,
    listener_id            text                     not null,
    event_type             text                     not null,
    serialized_event       text                     not null,
    publication_date       timestamp with time zone not null,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone
);

create index event_publication_archive_serialized_event_hash_idx on event_publication_archive using hash (serialized_event);
create index event_publication_archive_by_completion_date_idx on event_publication_archive (completion_date);
