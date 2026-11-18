-- Credenciales por ejecución: cada tarjeta que toma un runner trae su propio token (RUN), que vale solo para esa
-- tarjeta y ese rol, y solo mientras dura el préstamo (se revoca al liberarla o al vencer). La conexión larga de la
-- persona se queda en su máquina: el cliente de IA nunca la ve.
alter table access_token drop constraint access_token_kind_check;
alter table access_token add constraint access_token_kind_check check (kind in ('SESSION', 'AGENT', 'RUNNER', 'RUN'));
-- Alcance de un token RUN: «CLAVE|ROL» (p. ej. MED-12|BACKEND). Nulo en los demás.
alter table access_token add column scope varchar(80);
alter table access_token add constraint access_token_scope_chk check ((kind = 'RUN') = (scope is not null));

-- Sin clave foránea: el token se guarda por JPA en la misma transacción y se revoca por su id al cerrar el préstamo.
alter table runner_lease add column run_token_id uuid;
