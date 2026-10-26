# Runbook — hookledger

Operación de la Semana 1. Si algo no está acá, todavía no es operable.

## 1. Arrancar localmente

### Opción A — Docker Compose (recomendada)

```bash
cp .env.example .env
# cambiar DEMO_WEBHOOK_SECRET antes de exponer el servicio
docker compose up --build
```

Servicios:

| Servicio | Puerto | Notas |
|---|---|---|
| `app` | 8080 | Spring Boot; aplica Flyway al arrancar |
| `postgres` | 5432 | volumen `pgdata`, healthcheck con `pg_isready` |

### Opción B — Maven contra un PostgreSQL propio

```bash
export DB_URL=jdbc:postgresql://localhost:5432/hookledger
export DB_USER=hookledger
export DB_PASSWORD=hookledger
export DEMO_WEBHOOK_SECRET=dev-secret
mvn spring-boot:run
```

### Opción C — solo correr los tests (sin Docker)

```bash
mvn -B -ntp verify
```

Los tests levantan un PostgreSQL embebido y aplican las migraciones Flyway reales. Es la forma más
rápida de validar un cambio.

## 2. Verificar que está sano

```bash
curl -s localhost:8080/actuator/health | jq
```

Respuesta esperada (resumida):

```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "outbox": {"status": "UP", "details": {"pending": 0, "oldestAgeSeconds": 0}}
  }
}
```

Lectura:

| Señal | Significado | Acción |
|---|---|---|
| `outbox.pending` creciendo | el poller no drena | revisar logs de `OutboxScheduler`, conexiones a la DB |
| `outbox.oldestAgeSeconds` > 60 | backlog añejo | revisar publisher y tamaño de batch |
| `db` DOWN | PostgreSQL inaccesible | revisar contenedor/red/credenciales |
| `status` DOWN | algún componente falla | ir al componente en `components` |

Métricas:

```bash
curl -s localhost:8080/actuator/prometheus | grep '^hookledger'
```

| Métrica | Tipo | Uso |
|---|---|---|
| `hookledger_ingest_total{result="accepted"}` | counter | eventos nuevos |
| `hookledger_ingest_total{result="duplicate"}` | counter | reintentos del proveedor absorbidos |
| `hookledger_ingest_total{result="conflict"}` | counter | ids reutilizados con otro payload |
| `hookledger_outbox_published_total` | counter | filas publicadas |

## 3. Enviar un evento a mano

```bash
./scripts/send-event.sh demo-1
```

Variables:

| Variable | Default | Uso |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | destino |
| `DEMO_WEBHOOK_SECRET` | `dev-secret` | secreto de la fuente `demo` |
| `EVENT_ID` | `evt-<epoch>-<random>` | id de idempotencia |
| `BODY` | `{"type":"demo.ping",...}` | body crudo que se firma |

Probar idempotencia y conflicto:

```bash
EVENT_ID=demo-1 ./scripts/send-event.sh            # duplicate: false
EVENT_ID=demo-1 ./scripts/send-event.sh            # duplicate: true
EVENT_ID=demo-1 BODY='{"type":"demo.ping","data":{"v":2}}' ./scripts/send-event.sh
# HTTP 409, code=IDEMPOTENCY_CONFLICT
```

## 4. Errores frecuentes

| HTTP | `code` | Causa | Fix |
|---|---|---|---|
| 400 | `INVALID_SOURCE` / `INVALID_EVENT_ID` | falta header obligatorio | revisar el emisor |
| 400 | `INVALID_JSON` | body no es un objeto JSON | revisar el payload |
| 400 | `INVALID_TIMESTAMP` | timestamp no es epoch seconds | formato del emisor |
| 401 | `UNKNOWN_SOURCE` | `X-Hook-Source` no está configurada | agregar `hookledger.sources.<name>.secret` |
| 401 | `INVALID_SIGNATURE` | secreto distinto o body re-serializado | comparar con el body crudo |
| 401 | `TIMESTAMP_OUT_OF_WINDOW` | reloj desfasado > 300 s | sincronizar NTP o subir `MAX_CLOCK_SKEW_SECONDS` |
| 409 | `IDEMPOTENCY_CONFLICT` | mismo id, distinto payload | el proveedor reutilizó un id; investigar |
| 413 | `PAYLOAD_TOO_LARGE` | body > 256 KiB | subir `MAX_BODY_BYTES` o rechazar en el proveedor |

## 5. Rotar el secreto de una fuente

1. Coordinar con el proveedor una ventana con dos secretos válidos (o aceptar downtime de esa fuente).
2. Actualizar `DEMO_WEBHOOK_SECRET` (o el secreto de la fuente) en el entorno.
3. Reiniciar la app: los secretos se leen al construir `HookledgerProperties`.
4. Verificar con un evento firmado nuevo.
5. Confirmar que los eventos del secreto viejo empiezan a dar `401`.

Limitación actual: no hay soporte de dos secretos simultáneos. Se implementará si un proveedor real lo
exige.

## 6. Backlog de outbox

```bash
curl -s localhost:8080/v1/stats | jq
```

Si `outboxPending` no baja:

1. `docker compose logs -f app | grep -i outbox`
2. confirmar `OUTBOX_ENABLED=true`
3. confirmar que el proceso tiene conexiones libres (`DB_POOL_SIZE`)
4. Si el publisher externo está caído (Semana 2), el batch falla y las filas quedan pendientes: es el
   comportamiento correcto, no se pierden.

No borrar filas de `outbox_event` a mano. Si hace falta intervenir, hacerlo con el servicio detenido y
dejando registro de qué se descartó y por qué.

## 7. Apagar

```bash
docker compose down        # conserva el volumen
docker compose down -v     # borra datos; solo en desarrollo
```

## 8. Pendientes de operación (Semanas 2–3)

- Alertas: backlog > 5 min, tasa de `conflict` anómala, 5xx.
- Retención/archivado de `webhook_event` y `outbox_event`.
- Replay de eventos fallidos por CLI o endpoint admin.
- Despliegue en k3s con límites de recursos y probes reales.
- Runbook de caída de SQS/DLQ.
