-- La tarjeta del plan ya muestra los límites de personas y tableros: se quitan de las características
-- de fábrica (solo si nadie las editó todavía).
update plan set features = array['Agentes de IA en tu tablero', 'Plantillas de ventas, tareas y software', 'Avisos por correo y Telegram']
where code = 'FREE' and features = array['Hasta 3 personas y 2 tableros', 'Agentes de IA en tu tablero', 'Plantillas de ventas, tareas y software', 'Avisos por correo y Telegram'];

update plan set features = array['Agentes con tus propios roles y manuales', 'Aprobaciones por columna', 'Clientes con acceso de solo lectura']
where code = 'TEAM' and features = array['Hasta 15 personas y 10 tableros', 'Agentes con tus propios roles y manuales', 'Aprobaciones por columna', 'Clientes con acceso de solo lectura'];

update plan set features = array['Todo lo de Equipo', 'Fábrica de software: de la spec al release', 'Reparto de tareas en paralelo', 'Repos en GitHub y kit para Claude Code o Codex']
where code = 'FACTORY' and features = array['Todo lo de Equipo, sin límites', 'Fábrica de software: de la spec al release', 'Reparto de tareas en paralelo', 'Repos en GitHub y kit para Claude Code o Codex'];
