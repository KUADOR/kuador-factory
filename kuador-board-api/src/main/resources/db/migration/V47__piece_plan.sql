-- Las piezas planeadas de un proyecto nuevo, antes de crear el repo: son la fuente del stack para redactar los
-- documentos base, y el paso del repositorio arranca con ellas. Al armar el repo pasan a ser piezas reales.
create table piece_plan (
    workspace_id uuid        not null,
    project_key  varchar(10) not null,
    layout       varchar(12) not null check (layout in ('MONOREPO', 'MULTI_REPO')),
    pieces       text        not null, -- JSON: [{name, templateKey, repoName, variables}]
    updated_at   timestamptz not null,
    primary key (workspace_id, project_key),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);
