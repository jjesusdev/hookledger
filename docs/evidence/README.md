# Evidencia — Semanas 1–3 (en curso)

Este archivo registra qué está probado, cómo reproducirlo y qué **no** está probado todavía. La regla
del proyecto es simple: si un claim no tiene un comando o un test detrás, no va en el README.

Última actualización: **2026-10-26**.

## Entorno de la corrida

| Dato | Valor |
|---|---|
| OS | Ubuntu 26.04 (WSL 2) |
| JDK | Temurin 21.0.13 |
| Maven | 3.9.9 |
| PostgreSQL de tests | 14 embebido (Zonky `embedded-postgres` 2.2.2) |
| Docker local | **no disponible** (engine apagado); los smoke tests viven en CI |
| SQS local | LocalStack `3.8.1` en CI, sin credenciales de AWS |

## Resultado

```
mvn -B -ntp verify
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

CI público: [workflow `ci`](https://github.com/jjesusdev/hookledger/actions/workflows/ci.yml). Corrida
[40393018221](https://github.com/jjesusdev/hookledger/actions/runs/40393018221), 2026-10-26
(commit `952e935`):

| Job | Resultado |
|---|---|
| `test` | 30 tests, 0 fallos |
| `terraform` | `fmt -check`, `init -backend=false`, `validate` verdes |
| `build-image` | imagen Docker construida (sin push) |
| `compose-smoke` | Postgres + app, health `UP`, evento aceptado, duplicado, outbox drenado |
| `sqs-smoke` | LocalStack + SQS + worker → evento `DELIVERED` con intento `DELIVERED` |
| `dlq-replay-smoke` | 3 fallos → DLQ → replay → `DELIVERED` con 4 intentos auditados |
| `worker-down-smoke` | worker detenido → evento `PUBLISHED` en la cola → worker arriba → `DELIVERED` |
| `load-smoke` | k6 50/s por 30 s: 1501 eventos, p95 3,06 ms, 0 fallos, outbox drenado |

### Tests por clase

| Clase | Tests | Qué cubre |
|---|---|---|
| `HmacTest` | 6 | vector conocido HMAC-SHA256, firma válida, body alterado, timestamp expirado, fuente desconocida, firma no-hex |
| `HookledgerIntegrationTest` | 12 | ingesta + outbox atómicos, duplicado idempotente, conflicto de payload, firma inválida, timestamp expirado, 16 duplicados concurrentes, burst de demo, outbox con 2 publishers, receiver firmado, intentos de entrega, replay `FAILED` → `DELIVERED` |
| `SqsEventPublisherTest` | 2 | payload y atributos de correlación, falla rápido sin queue URL |
| `DeliveryServiceTest` | 3 | firma sobre los bytes exactos, non-2xx como fallo, target ausente |
| `SqsWorkerTest` | 5 | borra y marca `DELIVERED`, backoff 2 s y 4 s, último intento a DLQ, mensaje malformado |
| `ReplayServiceTest` | 2 | mueve DLQ → cola principal, exige ambas URLs |

## Claims y su reproducción

### 1. Un evento aceptado escribe evento y outbox en la misma transacción

```bash
mvn -B -ntp test -Dtest=HookledgerIntegrationTest#acceptsEventAndWritesOutboxInTheSameTransaction
```

El test verifica `webhook_event = 1` y `outbox_event = 1` después de un `POST /v1/events` exitoso. Si el
insert del outbox falla, la transacción revierte el evento (mismo `@Transactional` en `IngestService`).

### 2. Duplicados concurrentes producen un solo evento

```bash
mvn -B -ntp test -Dtest=HookledgerIntegrationTest#concurrentDuplicatesCreateOneEventAndOneOutboxRow
```

16 requests simultáneos con el mismo `(source, external_id)` y el mismo body → exactamente 1 respuesta
`duplicate: false`, 15 `duplicate: true`, 1 fila en cada tabla. La decisión la toma la restricción
`UNIQUE`, no un `SELECT` previo.

### 3. El mismo id con otro payload es un conflicto, no un duplicado

```bash
mvn -B -ntp test -Dtest=HookledgerIntegrationTest#duplicateWithDifferentPayloadConflicts
```

Respuesta `409` con `code=IDEMPOTENCY_CONFLICT` y `existingId`. No se escribe una segunda fila.

### 4. Una firma inválida se rechaza antes de persistir

```bash
mvn -B -ntp test -Dtest=HookledgerIntegrationTest#invalidSignatureIsRejectedBeforePersisting
```

Respuesta `401` y `webhook_event = 0`. La verificación ocurre antes de abrir trabajo de persistencia.

### 5. Dos publishers no reclaman la misma fila del outbox

```bash
mvn -B -ntp test -Dtest=HookledgerIntegrationTest#outboxPublisherClaimsEachRowExactlyOnceUnderConcurrency
```

100 eventos, 2 publishers en paralelo → 100 filas publicadas, `published_at IS NULL = 0`, y
`max(attempts) = 1`. Es la prueba de que `FOR UPDATE SKIP LOCKED` está haciendo su trabajo.

### 6. El endpoint de demo separa únicos de duplicados

```bash
mvn -B -ntp test -Dtest=HookledgerIntegrationTest#demoBurstReportsAcceptedAndDuplicateEvents
```

`count=20&duplicates=5` → `requested=25`, `accepted=20`, `duplicates=5`, 20 filas en la base.

### 7. El stack completo funciona en contenedores

```bash
docker compose up -d --build
# esperar /actuator/health = UP
./scripts/send-event.sh smoke-1   # duplicate: false
EVENT_ID=smoke-1 ./scripts/send-event.sh   # duplicate: true
curl -s localhost:8080/v1/stats           # outboxPending: 0
```

Ejecutado en CI (`compose-smoke`): health `UP`, primer evento aceptado, reintento marcado como duplicado
y backlog del outbox drenado a 0. Valida Dockerfile, `docker compose`, Flyway y la app real, no solo el
test embebido.

### 8. El camino SQS → worker → HTTP firmado entrega el evento

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml up -d --build
id="$(./scripts/send-event.sh sqs-1 | jq -r .id)"
# esperar unos segundos
curl -s "localhost:8080/v1/events/${id}" | jq
curl -s "localhost:8080/v1/events/${id}/deliveries" | jq
```

Salida real de la corrida `40387744478`:

```json
{
  "id": "915002dc-5379-49a9-ac42-2fe763eb32e2",
  "status": "DELIVERED",
  "receivedAt": "2026-10-26T19:36:49.675308Z",
  "publishedAt": "2026-10-26T19:36:49.852992Z"
}
```

```json
[{
  "attempt": 1,
  "target": "http://app:8080/v1/demo/receive",
  "statusCode": 200,
  "outcome": "DELIVERED"
}]
```

El worker recibió el mensaje de SQS, firmó el payload con el secreto del target, el receiver validó la
firma y el worker borró el mensaje. El estado del evento pasó de `RECEIVED` a `DELIVERED`.

### 9. Los fallos reintentan y el último intento queda para la DLQ

```bash
mvn -B -ntp test -Dtest=SqsWorkerTest
```

- Fallo en el intento 1 → `delivery_attempt(outcome=RETRY)`, `changeMessageVisibility` con 2 s y **no**
  delete. Test: `retriesWithVisibilityBackoffBeforeDeleting`.
- Fallo en el intento 2 → `RETRY` con 4 s. Test: `growsTheBackoffWithEachReceive`.
- Fallo en el intento 3 (`maxReceives=3`) → evento `FAILED`, `outcome=FAILED`, **no** delete y **no** se
  reprograma; en el siguiente receive SQS lo mueve a la DLQ por `RedrivePolicy`.
- Mensaje malformado → no rompe el poller; se reintenta y termina en la DLQ.

### 10. El replay mueve mensajes de la DLQ a la cola principal

```bash
mvn -B -ntp test -Dtest=ReplayServiceTest
curl -X POST -H 'X-Admin-Token: ...' 'localhost:8080/v1/admin/replay?max=10'
```

El test verifica `sendMessage` a la cola principal y `deleteMessage` en la DLQ. El endpoint no existe si
`ADMIN_TOKEN` está vacío. Un evento en `FAILED` que se reentrega con éxito pasa a `DELIVERED`; está
cubierto por `aReplayedEventMovesFromFailedToDelivered`.

### 11. La DLQ y el replay funcionan de punta a punta

El camino real está en el job `dlq-replay-smoke` de CI. Apunta el worker a
`http://app:8080/v1/demo/does-not-exist` (404), deja que agote los intentos, restaura un worker sano,
hace replay y verifica el evento y el historial:

```text
ingest: {"id":"6d352632-…","status":"RECEIVED","duplicate":false}
event failed after the configured receives: {"status":"FAILED",…}
dlq messages: 1
replay: {"replayed":1}
event delivered after replay: {"status":"DELIVERED",…}
deliveries: [{"attempt":1,"statusCode":404,"outcome":"RETRY"},
             {"attempt":2,"statusCode":404,"outcome":"RETRY"},
             {"attempt":3,"statusCode":404,"outcome":"FAILED"},
             {"attempt":4,"statusCode":200,"outcome":"DELIVERED"}]
dlq drained after replay
```

La secuencia de intentos sobrevive al replay: el broker reinicia su receive count, pero
`delivery_attempt` sigue creciendo. Ver corrida
[40392064502](https://github.com/jjesusdev/hookledger/actions/runs/40392064502), job `dlq-replay-smoke`.

### 12. Un worker detenido no pierde el evento

Job `worker-down-smoke`: con el stack arriba se detiene el worker, se ingesta un evento y se verifica
que el outbox lo publica y el mensaje espera en SQS:

```text
messages waiting: 1
event: {"status":"PUBLISHED",…}
```

Al volver a levantar el worker, el evento se entrega:

```text
event: {"id":"fa5acaee-…","status":"DELIVERED",…}
deliveries: [{"attempt":1,"statusCode":200,"outcome":"DELIVERED"}]
```

El evento no se perdió mientras no había consumidor; el outbox y la cola absorbieron la caída. Ver
corrida [40393018221](https://github.com/jjesusdev/hookledger/actions/runs/40393018221), job
`worker-down-smoke`.

### 13. Ingesta sostenida: 50 eventos por segundo con p95 de 3 ms

Job `load-smoke`, corrida
[40394469526](https://github.com/jjesusdev/hookledger/actions/runs/40394469526): k6 genera 1501
eventos firmados en 30 s (50/s), sin duplicados ni errores.

```text
http_reqs......................: 1501   50.03/s
http_req_duration..............: avg=2ms p(90)=2.56ms p(95)=3.06ms max=45.88ms
http_req_failed................: 0.00%  0 out of 1501
stats: {"total":1501,"received":0,"published":1501,"outboxPending":0}
```

Condiciones: runner de 4 vCPU, payloads de ~100 B, publisher de log y PostgreSQL en el mismo runner.
No es un número de producción; es una línea base reproducible para detectar regresiones. La prueba
mide la ingesta y el drenaje del outbox, no la entrega externa.

### 14. La firma del script coincide con el verificador

El script usa `openssl`, el servidor usa `javax.crypto.Mac`. Para comparar ambos caminos:

```bash
printf '%s.%s' 1700000000 '{"type":"demo.ping"}' \
  | openssl dgst -sha256 -hmac dev-secret -hex | sed 's/^.*= //'
# 152294cf87927cccf2740d7ba22a4569811d663329e536bead4f60801e61777b
```

El mismo vector está cubierto del lado Java por `HmacTest` (incluido el vector RFC de `HmacSHA256`).
Además, `scripts/send-event.sh` se ejecutó contra un servidor de referencia que recalcula el HMAC: los
tres casos (id automático, mismo id, `BODY` custom) llegaron con firma válida de 64 hex.

## Lo que **no** está probado todavía

Esto es tan importante como lo anterior:

- **AWS real**: Terraform está validado, no aplicado. No hay colas reales, ni IAM adjunto, ni alarmas
  conectadas a SNS. El smoke test usa LocalStack `3.8.1`.
- **Docker local**: el engine no corre en la máquina de desarrollo; los smoke tests viven en CI.
- **k3s / GHCR / demo pública**: todavía no desplegados. No hay URL pública.
- **Carga de entrega**: `load-smoke` mide la ingestión (50/s); no hay carga sostenida contra SQS y el
  worker, ni una prueba de saturación para encontrar el límite real.
- **Chaos**: caída de PostgreSQL y worker muerto a mitad de una entrega siguen sin prueba end-to-end.
  La DLQ, el replay y el worker detenido ya están cubiertos por `dlq-replay-smoke` y
  `worker-down-smoke`.
- **Rotación de secretos**: documentada en el runbook, no automatizada.

## Cómo mantener esto honesto

1. Cada PR que agregue un claim agrega su fila acá y el comando que lo reproduce.
2. Los números de tests se actualizan solo después de correr el comando, no de memoria.
3. Cuando algo deje de ser cierto, se borra el claim; no se deja “por si acaso”.
