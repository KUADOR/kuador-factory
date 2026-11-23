-- Lo que un tablero sabe hacer además del flujo (documentos, repositorio de código, reparto en tareas) deja de depender
-- de si es «de software»: cada tablero guarda sus capacidades. Nulo = las de su tipo (software: todas; los demás:
-- documentos), así los tableros que ya existen siguen igual.
alter table project
    add column capabilities text; -- JSON: ["DOCUMENTS","REPOSITORY","SPLIT"]
