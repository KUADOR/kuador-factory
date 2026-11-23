-- La tarjeta se adapta al negocio: cada tipo de tarjeta puede tener datos propios (monto, cliente, fecha…), definidos en
-- los tipos del tablero (project.issue_types, JSON) y guardados por tarjeta; y cada tablero dice en qué mide el tamaño
-- de sus tarjetas. Nulo = como antes (sin datos propios; puntos).
alter table issue
    add column custom_fields text; -- JSON: {"clave": "valor"}

alter table project
    add column estimate_unit varchar(10) check (estimate_unit in ('POINTS', 'HOURS', 'NONE'));
