-- Telegram por espacio: cada empresa puede tener su propio bot (token cifrado) y avisar también a grupos.
-- Sin bot propio se usa el de la plataforma, si está configurado.

create table telegram_bot (
    workspace_id   uuid         primary key references workspace (id) on delete cascade,
    token_enc      text         not null,
    username       varchar(64)  not null,
    webhook_secret varchar(64)  not null,
    updated_at     timestamptz  not null
);

create table telegram_group (
    id           uuid         primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    chat_id      bigint       not null,
    title        varchar(255) not null,
    on_assigned  boolean      not null default false,
    on_gates     boolean      not null default true,
    on_plans     boolean      not null default true,
    created_at   timestamptz  not null,
    unique (workspace_id, chat_id)
);

-- Código sin persona = código para vincular un grupo.
alter table telegram_link alter column member_id drop not null;
-- Aviso sin persona = aviso a un grupo.
alter table notification alter column member_id drop not null;
