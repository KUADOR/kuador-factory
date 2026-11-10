-- Color del texto sobre el color de marca (botones principales). Nulo = automático (el de mayor contraste).
alter table workspace
    add column brand_on_color varchar(7) check (brand_on_color is null or brand_on_color ~ '^#[0-9a-f]{6}$');
