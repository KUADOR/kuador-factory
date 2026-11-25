-- Los frenos a la fuerza bruta (intentos de sesión, altas, invitaciones) cuentan entre todas las réplicas de la API.
-- La clave (un correo, una IP) se guarda como huella, no en claro.
create table rate_hit (
    bucket   varchar(40) not null,
    key_hash char(64)    not null,
    at       timestamptz not null
);
create index rate_hit_key_idx on rate_hit (bucket, key_hash, at);
create index rate_hit_at_idx on rate_hit (at);
