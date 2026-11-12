-- Adjuntos de las tarjetas. El archivo vive en el almacenamiento compatible con S3 (Garage); aquí, sus datos.
create table attachment (
    id           uuid          primary key,
    workspace_id uuid          not null references workspace (id) on delete cascade,
    issue_key    varchar(30)   not null,
    file_name    varchar(255)  not null,
    content_type varchar(150)  not null,
    size_bytes   bigint        not null check (size_bytes >= 0),
    storage_key  varchar(300)  not null unique,
    uploaded_by  uuid,
    created_at   timestamptz   not null
);
create index attachment_issue_idx on attachment (workspace_id, issue_key, created_at);
