-- Presupuesto con freno: además del tope mensual del espacio, un tope por agente y uno por tarjeta (corta los bucles
-- en seco). Se avisa a quien administra al 80 % y al llegar al tope, una sola vez por mes y por tope. El runner, que
-- usa la suscripción de una persona, se limita por tiempo al día.
alter table workspace_ai add column card_cap_usd numeric(10, 2) check (card_cap_usd > 0);

create table agent_budget (
    agent_id     uuid primary key references member (id) on delete cascade,
    workspace_id uuid          not null references workspace (id) on delete cascade,
    monthly_usd  numeric(10, 2) not null check (monthly_usd > 0)
);

-- Avisos ya enviados: «WORKSPACE» o el id del agente, el mes (2026-11) y el nivel (80 o 100).
create table budget_alert (
    workspace_id uuid        not null references workspace (id) on delete cascade,
    scope        varchar(40) not null,
    month        varchar(7)  not null,
    level        int         not null,
    sent_at      timestamptz not null,
    primary key (workspace_id, scope, month, level)
);

alter table runner_machine add column daily_minutes int check (daily_minutes between 15 and 1440);
