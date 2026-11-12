-- Planes que se venden (los edita quien administra la plataforma) y el plan de cada espacio.

create table plan (
    code          varchar(30)   primary key check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    name          varchar(60)   not null,
    tagline       varchar(200)  not null,
    price_monthly numeric(10, 2) not null check (price_monthly >= 0),
    currency      varchar(3)    not null default 'USD',
    features      text[]        not null default '{}',
    max_members   integer       check (max_members > 0),  -- nulo = sin límite
    max_projects  integer       check (max_projects > 0), -- nulo = sin límite
    factory       boolean       not null default false,   -- fábrica de software: metodologías con agentes, reparto de tareas, repos
    trial_days    integer       not null default 0 check (trial_days >= 0),
    highlighted   boolean       not null default false,
    sort_order    integer       not null default 0,
    active        boolean       not null default true,
    updated_at    timestamptz   not null default now()
);

-- Un espacio sin fila aquí (instalación propia, creado antes de los planes) no tiene límites.
create table workspace_subscription (
    workspace_id  uuid         primary key references workspace (id) on delete cascade,
    plan_code     varchar(30)  not null references plan (code),
    status        varchar(20)  not null check (status in ('TRIAL', 'ACTIVE', 'PENDING_PAYMENT', 'CANCELED')),
    trial_ends_at timestamptz,
    paid_until    timestamptz,  -- nulo con ACTIVE = sin vencimiento (pago manual o cortesía)
    trial_used    boolean      not null default false,
    updated_at    timestamptz  not null
);

insert into plan (code, name, tagline, price_monthly, features, max_members, max_projects, factory, trial_days, highlighted, sort_order) values
    ('FREE', 'Inicio', 'Para ordenar el trabajo de un equipo pequeño.', 0,
     array['Hasta 3 personas y 2 tableros', 'Agentes de IA en tu tablero', 'Plantillas de ventas, tareas y software', 'Avisos por correo y Telegram'],
     3, 2, false, 0, false, 10),
    ('TEAM', 'Equipo', 'Para empresas que suman agentes a cada área.', 29,
     array['Hasta 15 personas y 10 tableros', 'Agentes con tus propios roles y manuales', 'Aprobaciones por columna', 'Clientes con acceso de solo lectura'],
     15, 10, false, 14, true, 20),
    ('FACTORY', 'Factory', 'Tu fábrica de software con siete agentes.', 79,
     array['Todo lo de Equipo, sin límites', 'Fábrica de software: de la spec al release', 'Reparto de tareas en paralelo', 'Repos en GitHub y kit para Claude Code o Codex'],
     null, null, true, 14, false, 30);

-- Los espacios que ya existen siguen con todo habilitado.
insert into workspace_subscription (workspace_id, plan_code, status, trial_used, updated_at)
select id, 'FACTORY', 'ACTIVE', true, now() from workspace;
