-- Recuperar la contraseña: el correo trae un enlace (token) y un código de 6 dígitos; sirve cualquiera de los dos.
-- Se guardan solo los hashes. Las credenciales son globales, igual que esta tabla.
create table password_reset (
    id          uuid        primary key,
    member_id   uuid        not null,
    email       varchar(254) not null,
    token_hash  varchar(64) not null unique,
    code_hash   varchar(64) not null,
    attempts    integer     not null default 0,
    expires_at  timestamptz not null,
    used_at     timestamptz,
    created_at  timestamptz not null
);
create index password_reset_email_idx on password_reset (email, created_at desc);
