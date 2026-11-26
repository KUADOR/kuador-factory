# Kuador runner

Deja trabajando a los agentes del tablero en tu máquina (Mac, Linux o Windows) con tu propio cliente de IA y tu suscripción: sin claves de API.

```
npx @kuador-npm/runner install --server https://<api-del-tablero>
```

La instalación:

1. Revisa que tengas Claude Code con tu sesión iniciada.
2. Muestra un código que apruebas en el tablero (Agentes locales → Aprobar una máquina), como al conectar una TV. Funciona igual por SSH.
3. Pregunta **qué roles atiende esta máquina**, cuántas tarjetas a la vez y, en tableros de software, dónde está el repo.
4. Lo deja como servicio que arranca solo: launchd (Mac), systemd de usuario (Linux) o el Programador de tareas (Windows).

## Cómo trabaja

- Escucha al tablero en tiempo real y, como respaldo, revisa cada 5 minutos.
- Pide una tarjeta: el tablero se la presta a la máquina por 3 minutos y el runner renueva el préstamo mientras el agente trabaja. Si la máquina se apaga, el préstamo vence y la tarjeta vuelve a la cola.
- Lanza `claude -p` en una carpeta por tarjeta (una copia aparte del repo con `git worktree` si es de código), con el tablero conectado por MCP, las instrucciones vigentes del rol y el modelo del agente.
- Lo que supervisas lo atiende tu máquina; lo libre lo toma la primera máquina del equipo con ese rol. Tres cortes en un día y la tarjeta deja de ofrecerse.
- Si tu suscripción llega a su límite, se pausa hasta que se renueve.

## Disco

Cada tarjeta trabaja en su propia copia del repo (`git worktree`), así varias avanzan en paralelo sin pisarse las ramas. La copia comparte el historial con el repo de origen: no es otro clon.

- **De dónde sale la copia:** del repo vinculado al tablero; si no hay, de otro repo vinculado que apunta al mismo repositorio de GitHub; si tampoco, de un clon propio del runner en `~/.kuador/repos`, uno por repositorio (nunca uno por tarjeta).
- **Al terminar cada tarjeta** se borra lo que dejó la compilación (`node_modules`, `build`, `target`, `dist`, `.next`, `.dart_tool`…), solo si git lo ignora: el código y los cambios del agente no se tocan. Si la tarjeta vuelve, se reinstala desde las cachés de `~/.npm` y `~/.m2`.
- **Temporales y herramientas:** cada agente recibe `TMPDIR` en `~/.kuador/tmp/<tarjeta>` (se borra al terminar) y una carpeta compartida `~/.kuador/tools` para SDKs con su versión (`flutter-3.50.1`), así se bajan una vez y no una por tarjeta. Lo que un agente deje igual en `/tmp` se anota y se borra tras un día sin uso (una hora si queda poco disco).
- **Procesos sueltos:** al terminar cada tarjeta se detienen los servidores y pruebas que el agente dejó corriendo en su carpeta; cada 6 horas, los de tarjetas que ya no están en curso.
- **Docker:** después de cada tarjeta se borran las imágenes huérfanas y la caché de construcción de más de un día; tus imágenes con nombre, contenedores y volúmenes no se tocan. Para apagarlo: `"dockerCleanup": false` en `runner.json`.
- **Cada 6 horas** se borran las carpetas de tarjetas sin uso hace más de 2 días (`keepDays`) que tienen su trabajo subido. Las que tienen cambios o commits sin subir se quedan y se avisa en el registro.
- **Con menos de 10 GB libres** (`minFreeGb`) primero limpia y, si no alcanza, deja de tomar tarjetas hasta que haya espacio.

## Comandos

| Comando | Qué hace |
|---|---|
| `install` | Conecta la máquina, elige roles e instala el servicio (`--no-service` para no instalarlo) |
| `roles` | Cambia los roles que atiende |
| `link <TABLERO> <carpeta>` | Vincula el repo de un tablero de software. Si el tablero trabaja en varios proyectos, uno por proyecto: `link <TABLERO>/<PROYECTO> <carpeta>` |
| `status` | Qué está haciendo ahora |
| `clean [--now]` | Libera disco ya (con `--now`, también las carpetas usadas hace poco que tienen su trabajo subido) |
| `pause` / `resume` | Deja de tomar tarjetas nuevas / vuelve a tomarlas |
| `start` | En primer plano (lo usa el servicio) |
| `uninstall` | Quita el servicio y la conexión |

Todo vive en `~/.kuador` (o `KUADOR_HOME`): `runner.json` con la conexión (solo legible por ti), `logs/`, `work/` y `repos/`. La conexión al tablero que usa Claude Code se escribe fuera de la carpeta de trabajo, nunca dentro del repo.

Codex y OpenCode llegan en la próxima versión.
