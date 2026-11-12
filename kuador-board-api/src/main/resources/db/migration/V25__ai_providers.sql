-- Proveedores de IA para los agentes que trabajan en el tablero. Los configura quien administra la plataforma:
-- dirección de la API, protocolo y los modelos que se ofrecen (los nombres cambian: no van en el código).
-- ANTHROPIC usa la ejecución de código de Anthropic; OPENAI_COMPATIBLE usa las herramientas de datos del tablero.
create table ai_provider (
    code       varchar(30)  primary key check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    name       varchar(60)  not null,
    protocol   varchar(20)  not null check (protocol in ('ANTHROPIC', 'OPENAI_COMPATIBLE')),
    base_url   varchar(200) not null,
    models     text[]       not null default '{}',
    key_hint   varchar(120),
    active     boolean      not null default true,
    sort_order integer      not null default 0,
    updated_at timestamptz  not null default now()
);

insert into ai_provider (code, name, protocol, base_url, models, key_hint, sort_order) values
    ('ANTHROPIC', 'Claude (Anthropic)', 'ANTHROPIC', 'https://api.anthropic.com', '{claude-opus-5-5,claude-sonnet-5-5,claude-haiku-5-5}',
     'Se crea en console.anthropic.com → API Keys. Empieza con sk-ant-.', 10),
    ('OPENAI', 'ChatGPT (OpenAI)', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', '{}',
     'Se crea en platform.openai.com → API keys (no sirve la suscripción de ChatGPT).', 20),
    ('DEEPSEEK', 'DeepSeek', 'OPENAI_COMPATIBLE', 'https://api.deepseek.com', '{}',
     'Se crea en platform.deepseek.com → API keys.', 30);

-- La clave del espacio es de un proveedor (uno a la vez; cambiar de proveedor reemplaza la clave).
alter table workspace_ai alter column provider type varchar(30);
alter table workspace_ai add constraint workspace_ai_provider_fk foreign key (provider) references ai_provider (code);
