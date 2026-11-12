-- Auditoría: quién hizo qué, cuándo, desde dónde y con qué resultado. Solo se agregan filas: no se editan ni se
-- borran (un disparador lo impide). Sin contenidos, contraseñas ni claves: acción, sobre qué y el resultado.
create table audit_log (
    id           uuid         primary key,
    workspace_id uuid,                         -- nulo: plataforma o sin espacio (p. ej. un login fallido)
    actor_id     uuid,
    actor_name   varchar(200),
    actor_kind   varchar(10)  not null check (actor_kind in ('HUMAN', 'AGENT', 'SYSTEM', 'ANONYMOUS')),
    action       varchar(120) not null,         -- «MÉTODO /ruta/{patrón}»; la etiqueta legible sale del código
    category     varchar(10)  not null check (category in ('SECURITY', 'WORK')),
    target       varchar(200),
    status       smallint     not null,
    ip           varchar(64),
    user_agent   varchar(300),
    occurred_at  timestamptz  not null
);

create index audit_log_workspace_idx on audit_log (workspace_id, occurred_at desc);
create index audit_log_time_idx on audit_log (occurred_at desc);

create function audit_log_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'La auditoría no se modifica ni se borra';
end;
$$;

create trigger audit_log_no_change before update or delete on audit_log
    for each row execute function audit_log_append_only();

create trigger audit_log_no_truncate before truncate on audit_log
    for each statement execute function audit_log_append_only();
