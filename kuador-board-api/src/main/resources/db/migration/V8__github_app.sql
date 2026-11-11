-- Fase 3: GitHub App de la plataforma y las organizaciones de GitHub conectadas por cada espacio.

-- La app es una sola para toda la plataforma (fila única). Sus secretos van cifrados (BOARD_SECRETS_KEY).
create table github_app (
    id             smallint     primary key default 1 check (id = 1),
    app_id         bigint       not null,
    slug           varchar(100) not null,
    name           varchar(100) not null,
    owner_login    varchar(100) not null,
    html_url       varchar(300) not null,
    client_id      varchar(100) not null,
    client_secret  text         not null,
    private_key    text         not null,
    webhook_secret text,
    registered_by  uuid,
    registered_at  timestamptz  not null
);

-- Una instalación de la app en una organización (o cuenta) de GitHub, conectada a un espacio.
create table github_installation (
    id              uuid         primary key,
    workspace_id    uuid         not null references workspace (id) on delete cascade,
    installation_id bigint       not null,
    account_login   varchar(100) not null,
    account_type    varchar(20)  not null,
    linked_by       uuid,
    linked_at       timestamptz  not null,
    unique (workspace_id, installation_id)
);

-- Estados de un solo uso para los viajes de ida y vuelta a GitHub (registrar la app, instalarla).
create table github_flow_state (
    state        varchar(64)  primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    purpose      varchar(20)  not null check (purpose in ('REGISTER', 'INSTALL')),
    member_id    uuid         not null,
    expires_at   timestamptz  not null
);

-- Repos vinculados con la app (sin token personal: el vínculo guarda el número de la conexión).
alter table project_repository drop constraint project_repository_kind_check;
alter table project_repository add constraint project_repository_kind_check check (kind in ('LOCAL', 'GITHUB', 'GITHUB_APP'));
