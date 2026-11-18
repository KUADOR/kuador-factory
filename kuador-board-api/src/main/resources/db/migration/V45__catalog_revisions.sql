-- Historial del catálogo: cada vez que se guarda o restaura una entrada (el manual de un rol, las reglas de agentes,
-- una metodología...) queda su versión con quién y cuándo. Se puede comparar con la actual y volver a cualquiera.
create table catalog_revision (
    id            uuid primary key,
    workspace_id  uuid         not null references workspace (id) on delete cascade,
    type          varchar(30)  not null,
    entry_key     varchar(40)  not null,
    version       bigint       not null,
    payload       text         not null,
    saved_by      uuid,
    saved_by_name varchar(120),
    saved_at      timestamptz  not null
);

create index catalog_revision_entry_idx on catalog_revision (workspace_id, type, entry_key, saved_at desc);
