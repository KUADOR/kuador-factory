-- Invitaciones por correo: la persona ya existe en el equipo; al aceptar elige su contraseña.
-- Se guarda solo el hash del token del enlace.
create table workspace_invitation (
    id           uuid         primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    member_id    uuid         not null,
    email        varchar(254) not null,
    token_hash   varchar(64)  not null unique,
    invited_by   uuid,
    expires_at   timestamptz  not null,
    accepted_at  timestamptz,
    created_at   timestamptz  not null
);
create index workspace_invitation_ws_idx on workspace_invitation (workspace_id) where accepted_at is null;
