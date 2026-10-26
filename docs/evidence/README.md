# Evidencia — Semana 1

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
| Docker local | **no disponible** (engine apagado), por eso los tests no lo usan |

## Resultado

```
mvn -B -ntp verify
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

CI público: [workflow `ci`](https://github.com/jjesusdev/hookledger/actions/workflows/ci.yml). Corrida
[40381456157](https://github.com/jjesusdev/hookledger/actions/runs/40381456157), 2026-10-26:

| Job | Resultado |
|---|---|
| `test` | 14 tests, 0 fallos |
| `build-image` | imagen Docker construida (sin push) |
| `compose-smoke` | `docker compose` levanta Postgres + app, health `UP`, evento firmado aceptado, reintento duplicado, outbox drenado a 0 |

| Clase | Tests | Qué cubre |
|---|---|---|
| `HmacTest` | 6 | vector conocido HMAC-SHA256, firma válida, body alterado, timestamp expirado, fuente desconocida, firma no-hex |
| `HookledgerIntegrationTest` | 8 | ingesta + outbox atómicos, duplicado idempotente, conflicto de payload, firma inválida, timestamp expirado, 16 duplicados concurrentes, burst de demo, outbox con 2 publishers |

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

Ejecutado en CI (`compose-smoke`, corrida 40381456157): health `UP`, primer evento aceptado, reintento
marcado como duplicado y backlog del outbox drenado a 0. Esto valida Dockerfile, `docker compose`,
Flyway y la app real, no solo el test embebido. La limitación es local: Docker Desktop no estaba
corriendo en la máquina de desarrollo, por eso la prueba vive en CI.

### 8. La firma del script coincide con el verificador

El script usa `openssl`, el servidor usa `javax.crypto.Mac`. Para comparar ambos caminos:

```bash
printf '%s.%s' 1700000000 '{"type":"demo.ping"}' \
  | openssl dgst -sha256 -hmac dev-secret -hex | sed 's/^.*= //'
# 152294cf87927cccf2740d7ba22a4569811d663329e536bead4f60801e61777b
```

El mismo vector está cubierto del lado Java por `HmacTest` (incluido el vector RFC de `HmacSHA256`).

Además, `scripts/send-event.sh` se ejecutó contra un servidor de referencia que recalcula el HMAC: los
tres casos (id automático, mismo id, `BODY` custom) llegaron con firma válida de 64 hex. Eso valida el
script, no la app; la app se valida con los tests de integración.

## Lo que **no** está probado todavía

Esto es tan importante como lo anterior:

- **Docker local**: `docker compose up` no se ejecuta en la máquina de desarrollo porque el engine no
  está corriendo; lo cubre el job `compose-smoke` en CI.
- **SQS / DLQ / retries / replay**: no existen aún; el publisher es `LoggingEventPublisher`.
- **Carga**: no hay prueba de throughput ni latencia. Llega en la Semana 3 con k6.
- **Chaos**: matar el worker a mitad de publicación, mensaje veneno, caída de PostgreSQL. Semana 3.
- **Demo pública**: no desplegada.
- **Rotación de secretos**: documentada en el runbook, no automatizada.

## Cómo mantener esto honesto

1. Cada PR que agregue un claim agrega su fila acá y el comando que lo reproduce.
2. Los números de tests se actualizan solo después de correr el comando, no de memoria.
3. Cuando algo deje de ser cierto, se borra el claim; no se deja “por si acaso”.
