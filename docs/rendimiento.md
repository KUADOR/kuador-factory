# Rendimiento y prueba de carga

Cómo se mide lo que la API aguanta, qué dio la última medición y qué se decidió con esos números.

## Cómo correrla

Con Docker, Java 25 y Node 22. Nunca contra producción: crea un tablero y miles de tarjetas (el script se niega a
correr contra una API que no sea local).

```bash
cd kuador-board-web && E2E_KEEP=1 pnpm e2e sesion   # deja arriba la base, la API y la web de pruebas
cd .. && node perf/carga.mjs                        # 5.000 tarjetas, 50 personas a la vez, 15 s por caso
BOARD=CARXXXX USERS=5 node perf/carga.mjs           # repite sobre el tablero ya sembrado, con otra carga
```

`CARDS`, `USERS`, `SECONDS` y `LIVE` cambian el tamaño. El tablero sembrado se parece a uno con historia: 1 de cada
50 tarjetas sigue en curso y el resto está terminado.

## Medición del 25 de noviembre de 2026

Una sola réplica de la API, Postgres 18 y el generador de carga en la misma máquina (Apple M4, 10 núcleos), 5.000
tarjetas en el tablero. Son números de una laptop: sirven para comparar un cambio con otro y para ver dónde se rompe,
no para prometer lo que hará el servidor.

**Uso normal (5 personas pidiendo sin parar)**

| Caso | Pedidos/s | p50 ms | p95 ms | Respuesta |
|---|---:|---:|---:|---:|
| Abrir el tablero (sin historial) | 406 | 11 | 18 | 96 KB |
| Listado completo (como abría antes) | 60 | 81 | 114 | 3.991 KB |
| Lista: una página de 50 | 589 | 8 | 12 | 40 KB |
| Lista: buscar por texto | 490 | 10 | 13 | 40 KB |
| Lista: filtrar por etiqueta y ordenar | 666 | 7 | 10 | 36 KB |
| Abrir una tarjeta | 1.867 | 3 | 4 | 1 KB |
| Configuración del tablero | 1.602 | 3 | 5 | 1 KB |

**Saturación (50 personas pidiendo sin parar, sin pausa entre pedidos)**

| Caso | Pedidos/s | p50 ms | p95 ms | p99 ms | Errores |
|---|---:|---:|---:|---:|---:|
| Abrir el tablero (sin historial) | 286 | 157 | 349 | 495 | 0 |
| Listado completo (como abría antes) | 50 | 1.031 | 1.437 | 1.669 | 0 |
| Lista: una página de 50 | 372 | 121 | 265 | 372 | 0 |
| Lista: buscar por texto | 315 | 134 | 342 | 485 | 0 |
| Lista: filtrar por etiqueta y ordenar | 438 | 100 | 231 | 327 | 0 |
| Abrir una tarjeta | 1.078 | 39 | 100 | 138 | 0 |
| Configuración del tablero | 898 | 48 | 118 | 165 | 0 |
| Comentar una tarjeta | 341 | 136 | 276 | 354 | 0 |

**Tiempo real:** 200 conexiones abiertas en 0,2 s; un cambio les llegó a las 200 en 26 ms. Después de 5.000 comentarios
seguidos, los avisos atrasados tardaron 12,6 s en terminar de llegar: los listeners de eventos (8 hilos) son lo que se
atrasa primero bajo una ráfaga, sin afectar a las peticiones.

## Qué se decidió con esto

- **El tablero sin historial cumple.** Con 5.000 tarjetas abre en 18 ms (p95) en uso normal, seis veces más rápido que
  el listado completo y con una respuesta 40 veces más chica. La meta era 300 ms.
- **Una ráfaga de cambios agotaba las conexiones a la base** (16 tarjetas creadas a la vez bastaban): la API pasaba
  30 s sin conexiones. Lo encontró esta prueba y está corregido (ejecutores sin «que lo corra quien lo pidió», pool de
  20). Hay una prueba de integración que lo vigila.
- **No se agrega caché del catálogo ni de la configuración del tablero** (paso 2.4 del plan). Leer la configuración
  toma 3 ms y aguanta 1.600 pedidos por segundo: un caché sumaría el riesgo de mostrar datos viejos entre réplicas a
  cambio de nada medible. Se vuelve a mirar si una medición futura lo señala.
- **El techo de una réplica** en esta máquina está cerca de 300–400 tableros abiertos por segundo; muy por encima del
  uso actual. Con dos réplicas el techo lo pone Postgres, no la API.

## Qué mirar en producción

En Grafana: `http_server_requests` (p95 por ruta), `hikaricp_connections_pending` (peticiones esperando conexión: debe
ser 0), `executor_queued_tasks` (cola de listeners) y `board_agents_waiting` (agentes esperando turno).
