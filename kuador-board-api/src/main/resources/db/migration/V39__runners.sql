-- Runner: el programa que deja trabajando a los agentes locales en la máquina de una persona (Mac, Linux o Windows),
-- con su propio cliente de IA y su suscripción. Se empareja una vez, declara qué roles atiende y toma tarjetas con un
-- préstamo que renueva mientras trabaja: si la máquina se apaga, el préstamo vence y la tarjeta vuelve a la cola.

-- Emparejamiento (como en una TV): el runner pide un código, la persona lo aprueba con su sesión en el tablero y el
-- runner recibe su conexión una sola vez. Solo se guardan hashes.
create table runner_pairing (
    device_hash  varchar(64) primary key,
    user_code    varchar(9)   not null unique,
    machine_name varchar(80)  not null,
    workspace_id uuid references workspace (id) on delete cascade,
    member_id    uuid references member (id) on delete cascade,
    expires_at   timestamptz  not null,
    consumed     boolean      not null default false
);

-- Cada máquina con runner: de quién es, qué roles atiende, cuántas tarjetas a la vez y su última señal de vida.
create table runner_machine (
    id           uuid primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    member_id    uuid         not null references member (id) on delete cascade,
    name         varchar(80)  not null,
    os           varchar(20)  not null,
    tool         varchar(20)  not null,
    roles        text         not null,
    capacity     int          not null check (capacity between 1 and 6),
    paused       boolean      not null default false,
    version      varchar(20),
    last_seen    timestamptz  not null,
    created_at   timestamptz  not null
);

create index runner_machine_workspace_idx on runner_machine (workspace_id);

-- Préstamos: qué máquina trabaja cada tarjeta. Uno activo por tarjeta (released_at nulo); los cerrados quedan como
-- historial corto (el freno de reintentos y la preferencia por quien ya trabajó la épica).
create table runner_lease (
    id           uuid primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    issue_key    varchar(30)  not null,
    epic_key     varchar(30),
    -- En qué columna estaba al tomarla: si el cliente de IA termina sin moverla, no avanzó.
    status       varchar(60)  not null,
    machine_id   uuid         not null references runner_machine (id) on delete cascade,
    member_id    uuid         not null references member (id) on delete cascade,
    agent_id     uuid         not null references member (id) on delete cascade,
    started_at   timestamptz  not null,
    expires_at   timestamptz  not null,
    released_at  timestamptz,
    outcome      varchar(20)
);

create unique index runner_lease_active_idx on runner_lease (workspace_id, issue_key) where released_at is null;
create index runner_lease_machine_idx on runner_lease (machine_id) where released_at is null;
create index runner_lease_recent_idx on runner_lease (workspace_id, started_at);
