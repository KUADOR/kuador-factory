-- Con más de una réplica de la API, una tarea programada la corre una sola a la vez: quien la toma deja aquí
-- su préstamo (hasta cuándo) y lo suelta al terminar. Si la réplica se cae, el préstamo vence solo.
create table cluster_lock (
    name         varchar(80) primary key,
    holder       uuid        not null,
    locked_until timestamptz not null
);
