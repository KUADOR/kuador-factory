-- El proyecto guarda lo suyo: su repositorio, sus piezas, su plan de piezas, sus documentos base y su manual de trabajo
-- ya no son de «tablero + proyecto» sino del proyecto. Se configura una vez (Organización → Proyectos) y lo usan todos los
-- tableros que lo trabajan. Los documentos de cada tarjeta siguen con su tablero.
--
-- Lo que ya existía: si un solo tablero guardó cosas de un proyecto, quedan en el proyecto. Si varios guardaron lo mismo,
-- se juntan. Si guardaron cosas distintas, el proyecto se parte: el primer tablero que lo sumó se queda con él y cada
-- tablero distinto recibe una copia, «Nombre (Tablero)», con lo suyo y con sus tarjetas. No se pierde nada.

alter table subject add column methodology varchar(40);

-- Qué tableros usan lo de sus proyectos (manual y documentos base): los de código y los que ya trabajaban con un manual.
-- Los demás (p. ej. marketing) siguen solo con los documentos de sus tarjetas. Nulo = las de su tipo (software: todas).
update project p set capabilities = (coalesce(p.capabilities, '["DOCUMENTS"]')::jsonb || '["PROJECT_DOCS"]'::jsonb)::text
 where not (p.capabilities is null and p.kind = 'SOFTWARE')
   and not coalesce(p.capabilities, '[]')::jsonb ? 'PROJECT_DOCS'
   and (coalesce(p.capabilities, '[]')::jsonb ? 'REPOSITORY'
        or p.methodology is not null
        or exists (select 1 from board_subject b where b.workspace_id = p.workspace_id and b.project_key = p.project_key and b.methodology is not null));

-- 1. Las reglas que atan estas filas a un tablero se van primero (para poder mover proyectos sin chocar con ellas).
do $$
declare
    c record;
begin
    for c in
        select con.conrelid::regclass as tbl, con.conname
          from pg_constraint con
         where con.contype = 'f'
           and con.conrelid in ('project_repository'::regclass, 'project_component'::regclass, 'piece_plan'::regclass,
                                'sync_pull_request'::regclass, 'document'::regclass)
           and con.confrelid in ('project'::regclass, 'board_subject'::regclass)
    loop
        execute format('alter table %s drop constraint %I', c.tbl, c.conname);
    end loop;
end $$;

-- 2. Qué guardó cada tablero de cada proyecto, resumido en una firma: dos tableros con la misma firma guardaron lo mismo.
create temporary table holding as
select bs.workspace_id, bs.project_key, bs.subject_key, bs.position, bs.added_at, bs.methodology,
       (select r.kind || '|' || r.location || '|' || coalesce(r.branch, '')
          from project_repository r
         where r.workspace_id = bs.workspace_id and r.project_key = bs.project_key and r.subject_key = bs.subject_key) as repo,
       (select string_agg((to_jsonb(c) - 'id' - 'workspace_id' - 'project_key' - 'subject_key' - 'created_at' - 'created_by')::text, ';' order by c.name)
          from project_component c
         where c.workspace_id = bs.workspace_id and c.project_key = bs.project_key and c.subject_key = bs.subject_key) as pieces,
       (select p.layout || '|' || p.pieces
          from piece_plan p
         where p.workspace_id = bs.workspace_id and p.project_key = bs.project_key and p.subject_key = bs.subject_key) as plan,
       (select string_agg(d.kind || '|' || d.path || '|' || d.title || '|' || md5(v.content), ';' order by d.kind)
          from document d join document_version v on v.document_id = d.id and v.version = d.current_version
         where d.workspace_id = bs.workspace_id and d.project_key = bs.project_key and d.subject_key = bs.subject_key
           and d.issue_key is null) as docs
  from board_subject bs;

alter table holding add column sig text;
update holding set sig = md5(concat_ws('#', coalesce(methodology, ''), coalesce(repo, ''), coalesce(pieces, ''), coalesce(plan, ''), coalesce(docs, '')))
 where methodology is not null or repo is not null or pieces is not null or plan is not null or docs is not null;

-- 3. Partir los proyectos con firmas distintas.
create function pg_temp.free_subject_key(ws uuid, base varchar) returns varchar language plpgsql as $$
declare
    stem varchar := left(base, 8);
    n    int := 2;
begin
    while exists (select 1 from subject where workspace_id = ws and subject_key = stem || n) loop
        n := n + 1;
    end loop;
    return stem || n;
end $$;

do $$
declare
    s       record;
    g       record;
    keeper  text;
    new_key varchar;
begin
    for s in select distinct workspace_id, subject_key from holding where sig is not null loop
        select h.sig into keeper from holding h
         where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig is not null
         order by h.added_at, h.position, h.project_key limit 1;
        for g in
            select h.sig, min(h.added_at) as first_added,
                   (array_agg(h.project_key order by h.added_at, h.position, h.project_key))[1] as first_board
              from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig is not null and h.sig <> keeper
             group by h.sig order by min(h.added_at)
        loop
            new_key := pg_temp.free_subject_key(s.workspace_id, s.subject_key);
            insert into subject (id, workspace_id, subject_key, name, description, created_at)
            select gen_random_uuid(), su.workspace_id, new_key,
                   left(su.name || ' (' || p.name || ')', 120), su.description, now()
              from subject su join project p on p.workspace_id = su.workspace_id and p.project_key = g.first_board
             where su.workspace_id = s.workspace_id and su.subject_key = s.subject_key;
            -- Los tableros de esa firma pasan a la copia, con lo que guardaban y con sus tarjetas.
            update board_subject b set subject_key = new_key
              from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and b.workspace_id = h.workspace_id and b.project_key = h.project_key and b.subject_key = h.subject_key;
            update project_repository t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update project_component t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update piece_plan t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update sync_pull_request t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update document t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update issue t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update ai_job t set subject_key = new_key from holding h
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig
               and t.workspace_id = h.workspace_id and t.project_key = h.project_key and t.subject_key = h.subject_key;
            update holding h set subject_key = new_key
             where h.workspace_id = s.workspace_id and h.subject_key = s.subject_key and h.sig = g.sig;
        end loop;
    end loop;
end $$;

-- 4. Varios tableros guardaron lo mismo de un proyecto: queda la copia del primero; las demás eran iguales.
create temporary table keeper as
select distinct on (workspace_id, subject_key) workspace_id, subject_key, project_key, methodology
  from holding where sig is not null
 order by workspace_id, subject_key, added_at, position, project_key;

delete from project_repository t using keeper k
 where t.workspace_id = k.workspace_id and t.subject_key = k.subject_key and t.project_key <> k.project_key;
delete from project_component t using keeper k
 where t.workspace_id = k.workspace_id and t.subject_key = k.subject_key and t.project_key <> k.project_key;
delete from piece_plan t using keeper k
 where t.workspace_id = k.workspace_id and t.subject_key = k.subject_key and t.project_key <> k.project_key;
delete from document t using keeper k
 where t.workspace_id = k.workspace_id and t.subject_key = k.subject_key and t.project_key <> k.project_key and t.issue_key is null;
-- Los PR de exportar documentos: el mismo número en el mismo proyecto es el mismo PR.
delete from sync_pull_request t
 where exists (select 1 from sync_pull_request o
                where o.workspace_id = t.workspace_id and o.subject_key = t.subject_key and o.number = t.number
                  and (o.created_at, o.id) < (t.created_at, t.id));

update subject s set methodology = k.methodology from keeper k
 where k.workspace_id = s.workspace_id and k.subject_key = s.subject_key;

drop table keeper;
drop table holding;

-- 5. Las reglas pasan a ser por proyecto.
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

alter table project_repository add constraint project_repository_subject_only_key unique (workspace_id, subject_key);
alter table project_component  add constraint project_component_subject_only_name_key unique (workspace_id, subject_key, name);
alter table piece_plan         add primary key (workspace_id, subject_key);
alter table sync_pull_request  add constraint sync_pull_request_subject_only_number_key unique (workspace_id, subject_key, number);

drop index document_scope_kind_idx;
create unique index document_scope_kind_idx on document (workspace_id, subject_key, coalesce(issue_key, ''), kind);
-- Una ruta del repositorio es de un solo documento base del proyecto; las de las tarjetas, por tablero (como antes).
create unique index document_base_path_idx on document (workspace_id, subject_key, path) where issue_key is null;
create unique index document_card_path_idx on document (workspace_id, project_key, subject_key, path) where issue_key is not null;

-- project_key queda como el tablero desde donde se guardó (dato, no regla). Borrar el proyecto se lleva lo suyo; borrar un
-- tablero se lleva solo los documentos de sus tarjetas (lo hace la aplicación).
alter table project_repository add foreign key (workspace_id, subject_key) references subject (workspace_id, subject_key) on delete cascade;
alter table project_component  add foreign key (workspace_id, subject_key) references subject (workspace_id, subject_key) on delete cascade;
alter table piece_plan         add foreign key (workspace_id, subject_key) references subject (workspace_id, subject_key) on delete cascade;
alter table sync_pull_request  add foreign key (workspace_id, subject_key) references subject (workspace_id, subject_key) on delete cascade;
alter table document           add foreign key (workspace_id, subject_key) references subject (workspace_id, subject_key) on delete cascade;

-- Los documentos de las tarjetas de un tablero que ya no existe (antes los borraba la base en cascada).
create index document_board_cards_idx on document (workspace_id, project_key) where issue_key is not null;

-- El manual ya no es del tablero: board_subject.methodology deja de usarse (se borra en una versión posterior).
comment on column board_subject.methodology is 'Sin uso desde V67 (ver subject.methodology); se borra en una versión posterior.';
