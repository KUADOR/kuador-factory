-- Una ejecución de agente en el servidor da señales de vida mientras trabaja. Si deja de darlas (la réplica se cayó o
-- se reinició), otra réplica la da por interrumpida y la retoma. Antes, cada arranque marcaba como interrumpidas TODAS
-- las que estuvieran corriendo, también las que seguían vivas en otra réplica.
alter table agent_run add column heartbeat_at timestamptz;
create index agent_run_running_idx on agent_run (coalesce(heartbeat_at, created_at)) where status = 'RUNNING';
