-- Equipos: las personas y los agentes que atienden uno o varios tableros. Un equipo se arma una vez (Organización →
-- Equipos) y cada tablero dice cuál lo atiende. Quien edita o solo mira ve los tableros de sus equipos; quien
-- administra, todos.
create table team (
    id           uuid primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    name         varchar(140) not null,
    created_at   timestamptz  not null
);

create unique index team_name_idx on team (workspace_id, lower(name));

-- Personas y agentes juntos: el tipo lo dice el miembro.
create table team_member (
    team_id      uuid        not null references team (id) on delete cascade,
    member_id    uuid        not null references member (id) on delete cascade,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    added_at     timestamptz not null default now(),
    primary key (team_id, member_id)
);

create index team_member_member_idx on team_member (workspace_id, member_id);

-- El equipo del tablero. Lo pone la aplicación al crearlo (nulo solo en un tablero creado sin pasar por ella).
alter table project add column team_id uuid references team (id);

-- Cada tablero que ya existe queda con un equipo de su mismo nombre, con las personas y los agentes que ya tenía:
-- nadie gana ni pierde acceso. Dos tableros con el mismo nombre en un espacio: el segundo lleva su clave.
create temporary table board_team as
select p.workspace_id, p.project_key, gen_random_uuid() as team_id, p.created_at,
       case when row_number() over (partition by p.workspace_id, lower(p.name) order by p.created_at, p.project_key) = 1
            then p.name else p.name || ' (' || p.project_key || ')' end as name
  from project p;

insert into team (id, workspace_id, name, created_at)
select team_id, workspace_id, name, created_at from board_team;

update project p set team_id = b.team_id
  from board_team b where b.workspace_id = p.workspace_id and b.project_key = p.project_key;

insert into team_member (team_id, member_id, workspace_id, added_at)
select b.team_id, x.member_id, x.workspace_id, min(x.added_at)
  from (select workspace_id, project_key, member_id, added_at from project_member
        union all
        select workspace_id, project_key, member_id, added_at from project_agent) x
  join board_team b on b.workspace_id = x.workspace_id and b.project_key = x.project_key
 group by b.team_id, x.member_id, x.workspace_id;

create index project_team_idx on project (team_id);
drop table board_team;

-- project_member y project_agent quedan de solo lectura (nadie las usa): se borran en una versión posterior.
comment on table project_member is 'Sin uso desde V66 (ver team_member); se borra en una versión posterior.';
comment on table project_agent is 'Sin uso desde V66 (ver team_member); se borra en una versión posterior.';
