# Kuador runner

Deja trabajando a los agentes del tablero en tu máquina (Mac, Linux o Windows) con tu propio cliente de IA y tu suscripción: sin claves de API.

```
npx @kuador/runner install --server https://<api-del-tablero>
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

## Comandos

| Comando | Qué hace |
|---|---|
| `install` | Conecta la máquina, elige roles e instala el servicio (`--no-service` para no instalarlo) |
| `roles` | Cambia los roles que atiende |
| `link <TABLERO> <carpeta>` | Vincula el repo de un tablero de software |
| `status` | Qué está haciendo ahora |
| `pause` / `resume` | Deja de tomar tarjetas nuevas / vuelve a tomarlas |
| `start` | En primer plano (lo usa el servicio) |
| `uninstall` | Quita el servicio y la conexión |

Todo vive en `~/.kuador` (o `KUADOR_HOME`): `runner.json` con la conexión (solo legible por ti), `logs/` y `work/`. La conexión al tablero que usa Claude Code se escribe fuera de la carpeta de trabajo, nunca dentro del repo.

Codex y OpenCode llegan en la próxima versión.
