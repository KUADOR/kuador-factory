-- Personas de cada tablero. Quien edita o solo mira ve únicamente los tableros donde está; los dueños y los
-- administradores del espacio ven todos (no necesitan fila). Los agentes siguen en project_agent.
create table project_member (
    workspace_id uuid        not null,
    project_key  varchar(10) not null,
    member_id    uuid        not null references member (id) on delete cascade,
    added_at     timestamptz not null default now(),
    primary key (workspace_id, project_key, member_id),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);

create index project_member_member_idx on project_member (workspace_id, member_id);

-- Nadie pierde acceso al desplegar: cada persona queda en los tableros que ya existen.
insert into project_member (workspace_id, project_key, member_id)
select p.workspace_id, p.project_key, m.id from project p join member m on m.workspace_id = p.workspace_id and m.kind = 'HUMAN';
