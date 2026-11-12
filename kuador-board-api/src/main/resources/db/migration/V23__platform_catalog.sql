-- Catálogo de la plataforma: lo edita quien administra Kuador Go y llega a los espacios al publicarlo.
-- payload = borrador vigente; published_payload = lo publicado (lo que reciben los espacios). Nulo = nunca publicado.
create table platform_catalog_entry (
    type              varchar(20) not null,
    entry_key         varchar(40) not null,
    payload           text        not null,
    published_payload text,
    -- Datos que no son de la entrada pero la acompañan (p. ej. nombre y color del agente de un rol).
    extra             text,
    updated_at        timestamptz not null,
    primary key (type, entry_key)
);

-- Versiones publicadas: qué entradas se agregaron y cuáles se actualizaron.
create table platform_catalog_release (
    version      integer     primary key,
    added        text        not null, -- JSON [{type,key}]
    updated      text        not null, -- JSON [{type,key}]
    published_at timestamptz not null
);

-- Paquetes por industria: qué plantillas se recomiendan y con qué tablero arranca un espacio nuevo.
create table catalog_package (
    code            varchar(30)  primary key check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    name            varchar(60)  not null,
    description     varchar(300) not null,
    templates       text[]       not null default '{}',
    starter_template varchar(40),
    starter_key     varchar(10),
    starter_name    varchar(120),
    sort_order      integer      not null default 0,
    active          boolean      not null default true,
    updated_at      timestamptz  not null default now()
);

insert into catalog_package (code, name, description, templates, starter_template, starter_key, starter_name, sort_order) values
    ('COMMERCE', 'Comercio y ventas', 'Tiendas, distribuidoras y negocios que venden: oportunidades, pedidos e informes de ventas con un analista.',
     '{SALES,TASKS}', 'SALES', 'VEN', 'Ventas', 10),
    ('SERVICES', 'Servicios y oficina', 'Consultoras, agencias y equipos de oficina: tareas del día a día, clientes y entregas.',
     '{TASKS,SALES}', 'TASKS', 'OPE', 'Operación', 20),
    ('SOFTWARE', 'Fábrica de software', 'Equipos que construyen software con agentes: de la spec al release.',
     '{SOFTWARE_FACTORY,SOFTWARE}', 'SOFTWARE', 'APP', 'Producto', 30);

-- El paquete que eligió cada espacio al registrarse (sus plantillas se recomiendan al crear un tablero).
create table workspace_package (
    workspace_id uuid        primary key references workspace (id) on delete cascade,
    package_code varchar(30) not null,
    chosen_at    timestamptz not null
);
