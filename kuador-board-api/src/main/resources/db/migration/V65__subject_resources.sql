-- Lo que es del proyecto y no del tablero: su repositorio, sus piezas, sus documentos y su manual de trabajo. Un tablero
-- que atiende dos sistemas tiene un repositorio y unos documentos por cada uno. Todo se guarda por tablero y proyecto:
-- lo que ya existía queda en el primer proyecto de su tablero.

-- El manual de trabajo con que el tablero trabaja cada proyecto (uno nuevo y uno que ya existe se trabajan distinto).
alter table board_subject add column methodology varchar(40);
update board_subject b set methodology = p.methodology
  from project p where p.workspace_id = b.workspace_id and p.project_key = b.project_key;

-- El trabajo que el asistente manda a la máquina de una persona recuerda de qué proyecto es (nulo = el primero).
alter table ai_job add column subject_key varchar(10);

alter table project_repository add column subject_key varchar(10);
alter table project_component  add column subject_key varchar(10);
alter table piece_plan         add column subject_key varchar(10);
alter table sync_pull_request  add column subject_key varchar(10);
alter table document           add column subject_key varchar(10);

-- El primer proyecto del tablero (o su propia clave, si aún no tiene ninguno guardado).
create function pg_temp.first_subject(ws uuid, board varchar) returns varchar language sql stable as $$
    select coalesce((select subject_key from board_subject where workspace_id = ws and project_key = board
                     order by position, added_at, subject_key limit 1), board)
$$;

update project_repository set subject_key = pg_temp.first_subject(workspace_id, project_key);
update project_component  set subject_key = pg_temp.first_subject(workspace_id, project_key);
update piece_plan         set subject_key = pg_temp.first_subject(workspace_id, project_key);
update sync_pull_request  set subject_key = pg_temp.first_subject(workspace_id, project_key);
-- El documento de una tarjeta va con el proyecto de la tarjeta; el del tablero, con el primero.
update document d set subject_key = coalesce(
    (select i.subject_key from issue i where i.workspace_id = d.workspace_id and i.issue_key = d.issue_key),
    pg_temp.first_subject(d.workspace_id, d.project_key));

-- Un tablero sin proyectos guardados (creado sin pasar por la aplicación) queda con el suyo, para poder enlazar lo demás.
insert into subject (id, workspace_id, subject_key, name, description, created_at)
select gen_random_uuid(), p.workspace_id, p.project_key, p.name, left(p.description, 2000), p.created_at from project p
 where not exists (select 1 from board_subject b where b.workspace_id = p.workspace_id and b.project_key = p.project_key)
   and not exists (select 1 from subject s where s.workspace_id = p.workspace_id and s.subject_key = p.project_key);
insert into board_subject (workspace_id, project_key, subject_key, position, added_at, methodology)
select p.workspace_id, p.project_key, p.project_key, 0, p.created_at, p.methodology from project p
 where not exists (select 1 from board_subject b where b.workspace_id = p.workspace_id and b.project_key = p.project_key);

alter table project_repository alter column subject_key set not null;
alter table project_component  alter column subject_key set not null;
alter table piece_plan         alter column subject_key set not null;
alter table sync_pull_request  alter column subject_key set not null;
alter table document           alter column subject_key set not null;

-- Las reglas de «uno por tablero» pasan a «uno por tablero y proyecto».
do $$
declare
    c record;
begin
    for c in
        select con.conrelid::regclass as tbl, con.conname
          from pg_constraint con
         where con.contype in ('u', 'p')
           and con.conrelid in ('project_repository'::regclass, 'project_component'::regclass, 'piece_plan'::regclass,
                                'sync_pull_request'::regclass, 'document'::regclass)
           and exists (select 1 from unnest(con.conkey) k join pg_attribute a on a.attrelid = con.conrelid and a.attnum = k
                        where a.attname = 'project_key')
    loop
        execute format('alter table %s drop constraint %I', c.tbl, c.conname);
    end loop;
end $$;

alter table project_repository add constraint project_repository_subject_key unique (workspace_id, project_key, subject_key);
alter table project_component  add constraint project_component_subject_name_key unique (workspace_id, project_key, subject_key, name);
alter table piece_plan         add primary key (workspace_id, project_key, subject_key);
alter table sync_pull_request  add constraint sync_pull_request_subject_number_key unique (workspace_id, project_key, subject_key, number);
alter table document           add constraint document_subject_path_key unique (workspace_id, project_key, subject_key, path);

-- Quitar un proyecto de un tablero se lleva lo que ese tablero guardaba de él.
alter table project_repository add foreign key (workspace_id, project_key, subject_key) references board_subject on delete cascade;
alter table project_component  add foreign key (workspace_id, project_key, subject_key) references board_subject on delete cascade;
alter table piece_plan         add foreign key (workspace_id, project_key, subject_key) references board_subject on delete cascade;
alter table sync_pull_request  add foreign key (workspace_id, project_key, subject_key) references board_subject on delete cascade;
alter table document           add foreign key (workspace_id, project_key, subject_key) references board_subject on delete cascade;

drop index document_scope_kind_idx;
create unique index document_scope_kind_idx on document (workspace_id, project_key, subject_key, coalesce(issue_key, ''), kind);
