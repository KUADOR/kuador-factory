-- Nivel de acceso de cada miembro: EDIT trabaja en el tablero; VIEW solo mira (por ejemplo, un
-- cliente) y, como mucho, cierra las puertas donde su rol es aprobador.
alter table member add column access varchar(8) not null default 'EDIT';
alter table member add constraint member_access_chk check (access in ('EDIT', 'VIEW'));
alter table member add constraint member_agent_edits_chk check (kind = 'HUMAN' or access = 'EDIT');
