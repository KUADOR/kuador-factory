-- Conflictos sin volver a Desarrollo: si el PR de una tarjeta que espera en una puerta (o en QA) queda en conflicto
-- después de otra fusión, la tarjeta se queda donde está y el runner de quien programa actualiza la rama. Aquí quedan
-- esos pedidos; los préstamos del runner dicen si son trabajo de la etapa (WORK) o un pedido de actualizar rama (SYNC).
create table branch_sync (
    workspace_id uuid         not null references workspace (id) on delete cascade,
    issue_key    varchar(20)  not null,
    project_key  varchar(10)  not null,
    repository   varchar(200) not null,
    pr_number    int          not null,
    branch       varchar(255) not null,
    base_branch  varchar(255) not null,
    role         varchar(40)  not null,
    note         text         not null,
    requested_at timestamptz  not null,
    attempts     int          not null default 0,
    primary key (workspace_id, issue_key)
);

alter table runner_lease add column kind varchar(10) not null default 'WORK' check (kind in ('WORK', 'SYNC'));
