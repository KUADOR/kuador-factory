-- Lo que un tablero sabe hacer además del flujo (documentos, repositorio de código, reparto en tareas) deja de depender
-- de si es «de software»: cada tablero guarda sus capacidades. Nulo = las de su tipo (software: todas; los demás:
-- documentos), así los tableros que ya existen siguen igual.
alter table project
    add column capabilities text; -- JSON: ["DOCUMENTS","REPOSITORY","SPLIT"]

-- El manual de trabajo (metodología) ya no es solo de software: lo lleva cualquier tablero que use documentos. La regla
-- la hace cumplir la aplicación con las capacidades del tablero.
do $$
declare
    c text;
begin
    for c in select conname from pg_constraint
             where conrelid = 'project'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%methodology%'
    loop
        execute format('alter table project drop constraint %I', c);
    end loop;
end $$;
