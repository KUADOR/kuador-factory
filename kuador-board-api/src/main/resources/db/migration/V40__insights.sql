-- Dashboard: cuántas horas de una persona vale un punto de historia en este espacio. Con eso se estiman las «horas
-- ahorradas» (lo que habría tardado una persona menos lo que tardaron los agentes). Sin fila = 4 horas.
create table insights_settings (
    workspace_id    uuid primary key references workspace (id) on delete cascade,
    hours_per_point numeric(5, 2) not null check (hours_per_point > 0 and hours_per_point <= 40),
    updated_at      timestamptz   not null
);

-- Las consultas del dashboard recorren movimientos y asignaciones por espacio y fecha.
create index activity_workspace_time_idx on activity (workspace_id, occurred_at) where type in ('CREATED', 'MOVED', 'ASSIGNED');
create index agent_run_workspace_time_idx on agent_run (workspace_id, created_at);
