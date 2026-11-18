-- Trabajos de IA para la máquina de una persona: cuando elige «En mi computador» (p. ej. al redactar los documentos
-- base sin gastar la clave de la empresa), el tablero arma el pedido y lo deja aquí; su runner lo toma, lo resuelve con
-- su Claude Code y devuelve el texto. La web espera el resultado.
create table ai_job (
    id            uuid primary key,
    workspace_id  uuid         not null references workspace (id) on delete cascade,
    member_id     uuid         not null references member (id) on delete cascade,
    project_key   varchar(10)  not null,
    step          varchar(20)  not null,
    system_prompt text         not null,
    user_prompt   text         not null,
    status        varchar(10)  not null check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    result        text,
    error         text,
    machine_id    uuid,
    created_at    timestamptz  not null,
    claimed_at    timestamptz,
    finished_at   timestamptz
);

create index ai_job_pending_idx on ai_job (workspace_id, member_id) where status = 'PENDING';
