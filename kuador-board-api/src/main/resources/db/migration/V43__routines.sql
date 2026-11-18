-- Rutinas: trabajo que se repite (el informe del lunes, los seguimientos de ventas, las publicaciones de la semana).
-- A su hora el tablero crea la tarjeta, la asigna (a un agente o a una persona) y sigue como cualquier otra. También
-- se disparan desde afuera con su webhook (un formulario, otra app). Si la anterior sigue abierta, por defecto no se
-- crea otra: así no se apilan.
create table routine (
    id             uuid primary key,
    workspace_id   uuid          not null references workspace (id) on delete cascade,
    project_key    varchar(10)   not null,
    title          varchar(200)  not null,
    description    text,
    status         varchar(30),
    assignee_id    uuid references member (id) on delete set null,
    frequency      varchar(10)   not null check (frequency in ('DAILY', 'WEEKLY', 'MONTHLY')),
    weekdays       varchar(20),
    month_day      int check (month_day between 1 and 31),
    at_time        varchar(5)    not null,
    timezone       varchar(60)   not null,
    enabled        boolean       not null default true,
    skip_if_open   boolean       not null default true,
    created_by     uuid references member (id) on delete set null,
    next_run_at    timestamptz,
    last_run_at    timestamptz,
    last_issue_key varchar(20),
    webhook_hash   varchar(64) unique,
    created_at     timestamptz   not null,
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

create index routine_due_idx on routine (next_run_at) where enabled;
create index routine_project_idx on routine (workspace_id, project_key);
