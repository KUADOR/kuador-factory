-- Consumo de IA: precios por modelo (los configura quien administra la plataforma, en USD por millón de tokens)
-- y un tope mensual opcional por espacio.
create table ai_model_price (
    model             varchar(60)    primary key,
    input_per_mtok    numeric(10, 4) not null check (input_per_mtok >= 0),
    output_per_mtok   numeric(10, 4) not null check (output_per_mtok >= 0),
    updated_at        timestamptz    not null
);

alter table workspace_ai add column monthly_budget_usd numeric(10, 2) check (monthly_budget_usd > 0);
