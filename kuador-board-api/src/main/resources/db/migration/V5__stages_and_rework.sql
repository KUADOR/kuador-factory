-- Fase 1: etapas con roles y puertas por columna, y freno de bucles.
alter table project
    add column stages     text    not null default '[]', -- JSON: [{column, roles, gate, approvers, rejectTo}]
    add column max_rework integer not null default 3 check (max_rework between 1 and 20);

-- Vueltas atrás de cada issue (de una etapa a otra anterior); al pasar del límite va a una persona.
alter table issue add column rework_count integer not null default 0;
