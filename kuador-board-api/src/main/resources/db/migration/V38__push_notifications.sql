-- Notificaciones push (la app instalada o el navegador): una suscripción por dispositivo de cada persona. Activarlas en
-- el dispositivo es el consentimiento; si el servicio de push dice que ya no existe, se borra.

create table push_subscription (
    id           uuid primary key,
    workspace_id uuid         not null references workspace (id) on delete cascade,
    member_id    uuid         not null references member (id) on delete cascade,
    endpoint     text         not null unique,
    p256dh       text         not null,
    auth         text         not null,
    user_agent   varchar(200),
    created_at   timestamptz  not null
);

create index push_subscription_member_idx on push_subscription (member_id);

alter table notification drop constraint notification_channel_check;
alter table notification add constraint notification_channel_check check (channel in ('EMAIL', 'TELEGRAM', 'PUSH'));
