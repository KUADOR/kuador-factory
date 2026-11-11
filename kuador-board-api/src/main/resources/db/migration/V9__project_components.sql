-- Piezas de cada proyecto (API, web, microservicios, microfrontends...): de qué plantilla nació cada
-- una y dónde vive (repo y carpeta). Monorepo: todas en el mismo repo, cada una en su carpeta. Un repo
-- por pieza: cada una en la raíz de su repo.
create table project_component (
    id               uuid         primary key,
    workspace_id     uuid         not null references workspace (id) on delete cascade,
    project_key      varchar(10)  not null,
    name             varchar(40)  not null,
    template_key     varchar(40),
    template_version bigint,
    template_repo    varchar(200),
    template_ref     varchar(200),
    kind             varchar(20)  not null,
    stack            varchar(120) not null,
    build_command    varchar(300),
    test_command     varchar(300),
    run_command      varchar(300),
    repo_location    varchar(200) not null,
    path             varchar(200) not null default '',
    installation_id  bigint,
    created_by       uuid,
    created_at       timestamptz  not null,
    unique (workspace_id, project_key, name),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

-- Nuevo catálogo: plantillas de pieza.
alter table catalog_entry drop constraint catalog_entry_type_check;
alter table catalog_entry add constraint catalog_entry_type_check
    check (type in ('WORKFLOW_TEMPLATE', 'METHODOLOGY', 'AGENT_ROLE', 'MCP_SERVER', 'COMPONENT_TEMPLATE'));
