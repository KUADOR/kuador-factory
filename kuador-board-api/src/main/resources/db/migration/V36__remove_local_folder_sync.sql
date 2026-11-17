-- Se quita la sincronización con una carpeta local del servidor: solo GitHub (con token o con la app). Los vínculos
-- de carpeta que existan se desvinculan como lo haría una persona: se olvida su estado de sincronización (los
-- documentos del tablero no se tocan).

delete from document_sync s
using document d, project_repository r
where s.document_id = d.id
  and r.workspace_id = d.workspace_id and r.project_key = d.project_key
  and r.kind = 'LOCAL';

delete from sync_pull_request p
using project_repository r
where r.workspace_id = p.workspace_id and r.project_key = p.project_key and r.kind = 'LOCAL';

delete from project_repository where kind = 'LOCAL';

alter table project_repository drop constraint project_repository_kind_check;
alter table project_repository add constraint project_repository_kind_check check (kind in ('GITHUB', 'GITHUB_APP'));
