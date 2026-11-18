-- El objetivo del tablero: para qué existe y qué le importa al negocio. Viaja en el contexto de cada tarjeta, así los
-- agentes deciden con brújula (qué priorizar, qué tan completo hacerlo, qué dejar afuera).
create table project_goal (
    workspace_id uuid          not null,
    project_key  varchar(10)   not null,
    goal         varchar(1000) not null,
    updated_at   timestamptz   not null,
    primary key (workspace_id, project_key),
    foreign key (workspace_id, project_key) references project (workspace_id, project_key) on delete cascade
);
