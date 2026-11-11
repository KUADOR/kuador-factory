-- Idiomas del espacio: el del equipo (documentos, comentarios, textos para el usuario final) y el del código.
alter table workspace add column team_language varchar(5) not null default 'es';
alter table workspace add column code_language varchar(5) not null default 'en';
