-- Proyecto: aquello sobre lo que se trabaja (un sistema, una marca, un cliente). El tablero es el equipo y su flujo;
-- un tablero atiende uno o varios proyectos y cada tarjeta dice a cuál pertenece. En el código se llama «subject»
-- porque la tabla «project» es el tablero.
create table subject (
    id           uuid primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    subject_key  varchar(10)  not null,
    name         varchar(120) not null,
    description  varchar(2000),
    created_at   timestamptz  not null,
    unique (workspace_id, subject_key)
);

-- Qué proyectos atiende cada tablero, en su orden: el primero es el que se propone al crear una tarjeta.
create table board_subject (
    workspace_id uuid        not null,
    project_key  varchar(10) not null,
    subject_key  varchar(10) not null,
    position     integer     not null default 0,
    added_at     timestamptz not null,
    primary key (workspace_id, project_key, subject_key),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade,
    foreign key (workspace_id, subject_key) references subject (workspace_id, subject_key)
);

create index board_subject_subject_idx on board_subject (workspace_id, subject_key);

-- Cada tablero que ya existe queda con un proyecto de su mismo nombre y clave: nada cambia para quien lo usa.
insert into subject (id, workspace_id, subject_key, name, description, created_at)
select gen_random_uuid(), workspace_id, project_key, name, left(description, 2000), created_at from project;

insert into board_subject (workspace_id, project_key, subject_key, position, added_at)
select workspace_id, project_key, project_key, 0, created_at from project;

-- El proyecto de la tarjeta. Nulo = el primero del tablero (tarjetas que entran sin pasar por la aplicación).
alter table issue add column subject_key varchar(10);
update issue set subject_key = project_key;
create index issue_subject_idx on issue (workspace_id, project_key, subject_key);
