-- La puerta que revisa un agente espera a que termine el CI del PR antes de llamarlo: aquí quedan las tarjetas que
-- esperan. Un vigilante las vuelve a mirar cada pocos minutos y, con el CI terminado, despierta al revisor.
create table gate_ci_wait (
    workspace_id uuid        not null references workspace (id) on delete cascade,
    issue_key    varchar(20) not null,
    project_key  varchar(10) not null,
    agent_id     uuid        not null references member (id) on delete cascade,
    requester_id uuid references member (id) on delete set null,
    since        timestamptz not null,
    checked_at   timestamptz not null,
    primary key (workspace_id, issue_key)
);

create index gate_ci_wait_due_idx on gate_ci_wait (checked_at);

-- Puertas guardadas con el límite anterior por defecto (400 líneas): los PR reales pasan de largo ese tamaño y todo
-- terminaba en una persona. El nuevo valor por defecto es 5000 (sin contar lockfiles ni archivos generados).
update project
set stages = regexp_replace(stages, '"maxChangedLines"\s*:\s*400([,}])', '"maxChangedLines":5000\1', 'g')
where stages ~ '"maxChangedLines"\s*:\s*400[,}]';
