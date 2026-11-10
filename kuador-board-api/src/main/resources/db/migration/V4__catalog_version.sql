-- Hasta qué versión del catálogo de fábrica recibió cada espacio (los existentes nacieron con la 1).
alter table workspace add column catalog_version integer not null default 1;
