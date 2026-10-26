# Runbook — hookledger

Operación de las Semanas 1–2. Si algo no está acá, todavía no es operable.

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

Este modo usa el publisher de log: valida ingesta, idempotencia y outbox, pero no la entrega.

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

### Opción D — camino completo con SQS y LocalStack

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml up --build -d
id="$(./scripts/send-event.sh sqs-1 | jq -r .id)"
sleep 3
curl -s "localhost:8080/v1/events/${id}" | jq
curl -s "localhost:8080/v1/events/${id}/deliveries" | jq
```

LocalStack crea `hookledger-events` y `hookledger-events-dlq` con `maxReceiveCount=3`. El worker corre
como un contenedor separado, con la misma imagen y `WORKER_ENABLED=true`.

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml down -v
```

La imagen de LocalStack está fijada en `3.8.1` a propósito: los releases `2026.x` piden
`LOCALSTACK_AUTH_TOKEN` y fallan sin licencia.

## 2. Variables de entorno

| Variable | Default | Uso |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/hookledger` | conexión a PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `hookledger` / `hookledger` | credenciales de la DB |
| `DB_POOL_SIZE` | `10` | conexiones máximas del pool |
| `DEMO_WEBHOOK_SECRET` | `dev-secret` | secreto de la fuente `demo`; solo desarrollo |
| `MAX_CLOCK_SKEW_SECONDS` | `300` | ventana de validez del timestamp |
| `MAX_BODY_BYTES` | `200000` | límite de body; deja margen bajo los 256 KiB de SQS |
| `OUTBOX_ENABLED` | `true` | activa el poller del outbox |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | frecuencia del poller |
| `OUTBOX_BATCH_SIZE` | `50` | filas por ciclo |
| `PUBLISHER` | `log` | `log` o `sqs` |
| `SQS_QUEUE_URL` | vacío | cola principal; obligatoria con `PUBLISHER=sqs` o worker activo |
| `SQS_DLQ_URL` | vacío | DLQ; obligatoria para replay |
| `AWS_REGION` | `us-east-1` | región del cliente SQS |
| `AWS_ENDPOINT_URL` | vacío | override para LocalStack; vacío en AWS |
| `WORKER_ENABLED` | `false` | activa el consumidor SQS |
| `WORKER_WAIT_TIME_SECONDS` | `5` | long polling (0–20) |
| `WORKER_MAX_MESSAGES` | `10` | mensajes por receive (1–10) |
| `WORKER_MAX_RECEIVES` | `3` | debe coincidir con `maxReceiveCount` de la cola |
| `WORKER_VISIBILITY_TIMEOUT_SECONDS` | `30` | tiempo que el worker posee el mensaje |
| `WORKER_BACKOFF_BASE_SECONDS` | `2` | base del backoff exponencial |
| `WORKER_BACKOFF_MAX_SECONDS` | `60` | techo del backoff |
| `DEMO_TARGET_URL` | `http://localhost:8080/v1/demo/receive` | target del worker en la demo |
| `DEMO_TARGET_SECRET` | `dev-target-secret` | secreto con el que el worker firma al target |
| `ADMIN_TOKEN` | vacío | habilita `/v1/admin/replay`; vacío = endpoint ausente |
| `DEMO_ENABLED` | `true` | expone `/v1/demo/*`; apagar en producción |

## 3. Verificar que está sano

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
| `outbox.pending` creciendo | el poller no drena; si `PUBLISHER=sqs`, SQS puede estar caído o sin permisos | revisar logs de `OutboxScheduler`, credenciales y endpoint |
| `outbox.oldestAgeSeconds` > 60 | backlog añejo | revisar publisher, batch y conectividad |
| `db` DOWN | PostgreSQL inaccesible | revisar contenedor/red/credenciales |
| `status` DOWN | algún componente falla | ir al componente en `components` |

El health no consulta SQS ni el worker: un SQS caído se ve como `outboxPending` creciendo, no como
`status: DOWN`. Para el worker, la señal es el log y las métricas de entrega.

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
| `hookledger_delivery_total{result="delivered"}` | counter | entregas con 2xx |
| `hookledger_delivery_total{result="retry"}` | counter | fallos que vuelven a la cola |
| `hookledger_delivery_total{result="failed"}` | counter | eventos que agotaron intentos y van a la DLQ |
| `hookledger_replay_total` | counter | mensajes movidos de la DLQ a la cola principal |

## 4. Enviar un evento a mano

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

## 5. Errores frecuentes

| HTTP | `code` | Causa | Fix |
|---|---|---|---|
| 400 | `INVALID_SOURCE` / `INVALID_EVENT_ID` | falta header obligatorio | revisar el emisor |
| 400 | `INVALID_JSON` | body no es un objeto JSON | revisar el payload |
| 400 | `INVALID_TIMESTAMP` | timestamp no es epoch seconds | formato del emisor |
| 400 | `INVALID_REPLAY` | `max` fuera de 1–10 | ajustar el parámetro |
| 401 | `UNKNOWN_SOURCE` | `X-Hook-Source` no está configurada | agregar `hookledger.sources.<name>.secret` |
| 401 | `INVALID_SIGNATURE` | secreto distinto o body re-serializado | comparar con el body crudo |
| 401 | `TIMESTAMP_OUT_OF_WINDOW` | reloj desfasado > 300 s | sincronizar NTP o subir `MAX_CLOCK_SKEW_SECONDS` |
| 401 | `UNAUTHORIZED` | token admin incorrecto o ausente | revisar `ADMIN_TOKEN` y el header `X-Admin-Token` |
| 409 | `IDEMPOTENCY_CONFLICT` | mismo id, distinto payload | el proveedor reutilizó un id; investigar |
| 413 | `PAYLOAD_TOO_LARGE` | body > `MAX_BODY_BYTES` (200 000) | achicar el payload o usar claim check en S3 |
| 500 | `TARGET_NOT_CONFIGURED` | el receiver de demo no tiene secreto | configurar `DEMO_TARGET_SECRET` |

## 6. Entregas: reintentos y backoff

El worker solo borra el mensaje cuando el target responde 2xx. Ante un fallo (non-2xx, timeout, DNS,
conexión rechazada) programa el reintento con `changeMessageVisibility`:

```
delay(n) = min(WORKER_BACKOFF_BASE_SECONDS * 2^(n-1), WORKER_BACKOFF_MAX_SECONDS)
```

Con los defaults (base 2 s, techo 60 s): intento 1 → 2 s, intento 2 → 4 s, intento 3 → 8 s. El delay
se aplica después de que el intento terminó, así que no necesita cubrir el tiempo de procesamiento.

Secuencia completa con `WORKER_MAX_RECEIVES=3` y `maxReceiveCount=3`:

1. Intento 1 falla → `delivery_attempt(outcome=RETRY)`, evento sigue `PUBLISHED`.
2. Intento 2 falla → otro `RETRY`, con backoff de 4 s.
3. Intento 3 falla → `delivery_attempt(outcome=FAILED)` y el evento pasa a `FAILED`. El mensaje **no**
   se borra ni se extiende: en el siguiente receive SQS lo mueve a la DLQ.
4. La DLQ lo retiene 14 días (Terraform) y dispara la alarma `dlq-not-empty` si hay un topic SNS.

La entrega es **at-least-once**, no exactly-once:

- Si el worker muere después del 2xx del target y antes del `deleteMessage`, SQS reentrega.
- Si el outbox publica y el proceso muere antes del commit, el mensaje puede duplicarse.
- El target debe deduplicar por `X-Hook-Id`. `delivery_attempt` es idempotente por
  `(event_id, attempt)`, pero eso no evita una segunda llamada HTTP.

`delivery_attempt.attempt` es una **secuencia por evento**, no el receive count de SQS. Un replay
desde la DLQ reinicia el conteo del broker, pero el historial sigue creciendo: si el evento falló en
los intentos 1–3 y el replay entrega en el intento 4, se ven las cuatro filas. Esa secuencia es la que
devuelve `GET /v1/events/{id}/deliveries`.

Cómo revisar un evento:

```bash
curl -s "localhost:8080/v1/events/${id}" | jq
curl -s "localhost:8080/v1/events/${id}/deliveries" | jq
```

## 7. DLQ y replay

Inspeccionar la DLQ (LocalStack):

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml \
  exec -T localstack awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/hookledger-events-dlq \
  --attribute-names ApproximateNumberOfMessages
```

Replay manual, con el token configurado en `ADMIN_TOKEN`:

```bash
curl -s -X POST -H "X-Admin-Token: ${ADMIN_TOKEN}" \
  "localhost:8080/v1/admin/replay?max=10" | jq
# {"replayed": 3}
```

Reglas:

- `max` va de 1 a 10 para no bloquear el request con una DLQ enorme.
- Sin `ADMIN_TOKEN`, el endpoint no se registra (404).
- El replay es at-least-once: si el proceso muere entre `sendMessage` y `deleteMessage`, el mensaje
  puede volver a la cola principal. No se pierde, se duplica.
- Un evento en `FAILED` que se reentrega con éxito pasa a `DELIVERED`; el historial de intentos queda
  en `delivery_attempt`, con un intento nuevo (no se reutiliza el número).
- Antes de reenviar, corregir la causa: target caído, URL mal configurada, secreto distinto o payload
  venenoso. Reenviar un mensaje veneno solo gasta intentos y vuelve a llenar la DLQ.

El ciclo completo (fallo sostenido → DLQ → replay → entrega) está cubierto por el job
`dlq-replay-smoke` en CI: worker apuntado a un target que devuelve 404, tres intentos, mensaje en la
DLQ, worker sano, replay y evento `DELIVERED` con el historial de intentos intacto.

Si la DLQ crece de forma sostenida, el problema no es la DLQ: es el target o el payload. La DLQ es el
lugar donde se conserva la evidencia.

## 8. Backlog de outbox

```bash
curl -s localhost:8080/v1/stats | jq
```

Si `outboxPending` no baja:

1. `docker compose logs -f app | grep -i outbox`
2. confirmar `OUTBOX_ENABLED=true`
3. confirmar que el proceso tiene conexiones libres (`DB_POOL_SIZE`)
4. confirmar que SQS responde y que las credenciales alcanzan (`PUBLISHER=sqs`)
5. si el publisher falla, el batch se reintenta y las filas quedan pendientes: es el comportamiento
   correcto, no se pierden.

No borrar filas de `outbox_event` a mano. Si hace falta intervenir, hacerlo con el servicio detenido y
dejando registro de qué se descartó y por qué.

## 9. Rotar secretos

### Secreto de una fuente (entrada)

1. Coordinar con el proveedor una ventana con dos secretos válidos (o aceptar downtime de esa fuente).
2. Actualizar `DEMO_WEBHOOK_SECRET` (o el secreto de la fuente) en el entorno.
3. Reiniciar la app: los secretos se leen al construir `HookledgerProperties`.
4. Verificar con un evento firmado nuevo.
5. Confirmar que los eventos del secreto viejo empiezan a dar `401`.

Limitación actual: no hay soporte de dos secretos simultáneos. Se implementará si un proveedor real lo
exige.

### Secreto del target (salida)

1. Actualizar el secreto en el receptor.
2. Actualizar `DEMO_TARGET_SECRET` (o el secreto del target) en la app y en el worker.
3. Reiniciar ambos procesos.
4. Revisar `delivery_attempt`: si el receptor quedó con el secreto viejo, los intentos dan `401` y el
   mensaje termina en la DLQ.

## 10. Apagar

```bash
docker compose down        # conserva el volumen
docker compose down -v     # borra datos; solo en desarrollo

# stack con SQS/LocalStack
docker compose -f docker-compose.yml -f docker-compose.sqs.yml down -v
```

## 11. Pendientes de operación (Semana 3)

- Aplicar Terraform en AWS real (requiere región, credenciales y confirmación de costos).
- Despliegue en k3s con límites de recursos y probes reales; GHCR para la imagen.
- Grafana con ingesta, backlog del outbox, entregas y DLQ; alertas SLO.
- Prueba de carga con k6 y escenarios de caos (worker kill, PostgreSQL caído, mensaje veneno).
- Replay end-to-end contra LocalStack, no solo unit tests.
- Retención/archivado de `webhook_event`, `outbox_event` y `delivery_attempt`.
- Rate limiting en `/v1/events` y en el endpoint de demo.
- Runbook de caída de SQS: qué hacer si la cola no existe, si IAM falla o si la región está mal.
