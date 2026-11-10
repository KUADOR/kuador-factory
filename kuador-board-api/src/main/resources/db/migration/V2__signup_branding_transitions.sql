-- Alta de espacios (SaaS) con verificación por correo, marca del espacio y reglas de transición.
-- Migración aditiva: no toca datos existentes.

-- ---------------------------------------------------------------- Marca del espacio
alter table workspace
    add column description text,
    add column brand_color varchar(7) not null default '#ffb81c' check (brand_color ~ '^#[0-9a-f]{6}$'),
    -- Logo como data URL (png, jpeg o webp; tope en la aplicación). Se muestra con <img>, nunca como HTML.
    add column logo        text;

-- ---------------------------------------------------------------- Verificación de correo
-- Un código de 6 dígitos por solicitud. Solo se guarda su hash. Al verificarlo se emite un token de
-- alta (también hasheado) que autoriza a crear el espacio durante un rato.
create table email_verification (
    id               uuid primary key,
    email            varchar(254) not null,
    code_hash        varchar(64)  not null,
    attempts         integer      not null default 0,
    expires_at       timestamptz  not null,
    verified_at      timestamptz,
    signup_token_hash varchar(64) unique,
    signup_expires_at timestamptz,
    used_at          timestamptz,
    created_at       timestamptz  not null
);

create index email_verification_email_idx on email_verification (email, created_at desc);

-- ---------------------------------------------------------------- Reglas de transición
-- Vacío o restricted = false: cualquier columna a cualquier otra (como hasta ahora).
alter table project
    add column transitions_restricted boolean not null default false,
    add column transitions            text    not null default '[]'; -- JSON: [{from, to}] por clave de columna
