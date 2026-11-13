-- Niveles de acceso del espacio. OWNER: el plan, los pagos y quién administra (siempre queda al menos uno).
-- ADMIN: el equipo, las invitaciones, el catálogo, la IA, GitHub, los repositorios y la auditoría.
-- EDIT: trabaja en los tableros. VIEW: solo mira (y cierra las puertas donde su rol aprueba).
alter table member drop constraint member_access_chk;
alter table member add constraint member_access_chk check (access in ('OWNER', 'ADMIN', 'EDIT', 'VIEW'));

-- Quien creó el espacio (rol OWNER) pasa a ser dueño.
update member set access = 'OWNER' where kind = 'HUMAN' and role = 'OWNER' and access = 'EDIT';

-- Un espacio sin dueño: la persona más antigua que edita.
update member set access = 'OWNER'
where id in (
    select distinct on (workspace_id) id from member
    where kind = 'HUMAN' and access = 'EDIT'
      and workspace_id not in (select workspace_id from member where access = 'OWNER')
    order by workspace_id, created_at, id);

-- El resto de quienes editaban administraba todo: siguen como administradores para no quitarle nada a nadie.
-- El dueño puede bajarlos a EDIT desde Equipo.
update member set access = 'ADMIN' where kind = 'HUMAN' and access = 'EDIT';
