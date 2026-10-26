# hookledger

[![ci](https://github.com/jjesusdev/hookledger/actions/workflows/ci.yml/badge.svg)](https://github.com/jjesusdev/hookledger/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

Plataforma de ingesta y entrega confiable de webhooks: valida la firma, deduplica, persiste y
publica por un outbox transaccional. Diseñada para el caso aburrido y caro: el proveedor reintenta,
el consumidor se cae y nadie sabe qué evento se perdió.

> **Estado: Semana 1 de 3.** Ya existen ingesta HMAC, idempotencia atómica en PostgreSQL y outbox
> transaccional con tests. El transporte actual es un adaptador de log; SQS + worker + DLQ + replay,
> k3s y Grafana llegan en las semanas 2–3. No hay demo pública todavía.

**Resumen en español:** recibe webhooks de terceros, rechaza firmas inválidas, evita procesar dos
veces el mismo evento y garantiza que el evento y su mensaje saliente se escriban en la misma
transacción. La entrega es *at-least-once* + deduplicación, no un imposible *exactly-once*.

---

## El problema

Integrar pagos, WhatsApp, ERP o GitHub termina siempre en el mismo código frágil:

- se valida la firma a mano (o no se valida);
- se hace `SELECT` y luego `INSERT` para deduplicar, con una ventana de carrera;
- se publica en la cola dentro del request, y si el commit falla el mensaje queda huérfano;
- si el consumidor falla, el evento se pierde o queda en un log que nadie lee;
- no hay métricas para responder “¿cuántos eventos están atrasados?”.

`hookledger` concentra ese problema en un solo servicio con decisiones explícitas y verificables.

## Qué hace hoy (Semana 1)

- `POST /v1/events` con firma HMAC-SHA256 sobre `timestamp + "." + body`.
- Ventana de reloj configurable (5 minutos por defecto) para limitar replay.
- Idempotencia atómica: `UNIQUE (source, external_id)` + `ON CONFLICT DO NOTHING`.
- Mismo evento y mismo payload → `200` con `duplicate: true`.
- Mismo evento y payload distinto → `409 IDEMPOTENCY_CONFLICT`.
- `webhook_event` y `outbox_event` se escriben en **la misma transacción**.
- Poller de outbox con `FOR UPDATE SKIP LOCKED` (seguro con múltiples publishers).
- Estado por evento, métricas de ingesta y salud del backlog en `/actuator/health`.
- Endpoint de demo para generar 100 eventos con 10% de duplicados.

## Arquitectura

```
proveedor ──HMAC──► POST /v1/events
                        │
                        ├─ webhook_event   (unique source + external_id)
                        └─ outbox_event    (misma transacción)
                                  │
                        OutboxScheduler (FOR UPDATE SKIP LOCKED)
                                  │
                        EventPublisher ──► log        (semana 1)
                                       └─► SQS + DLQ  (semana 2)
```

**Por qué outbox y no publicar dentro del request:** si publicas a la cola y después falla el commit,
el mensaje existe pero el evento no. Si haces commit y después publicas, el evento existe pero el
mensaje no. El outbox elimina el dual-write: PostgreSQL es la fuente de verdad y la cola es derivada.

**Por qué `ON CONFLICT` y no `findUnique` + `update`:** dos entregas concurrentes del mismo webhook
pueden pasar el `SELECT` antes de que cualquiera escriba. La base de datos decide el ganador.

## Quick start

### Docker Compose

```bash
git clone https://github.com/jjesusdev/hookledger.git
cd hookledger
docker compose up --build
```

### Enviar un evento firmado

```bash
./scripts/send-event.sh demo-1
```

```json
{"id":"f0f0e4b8-...","status":"RECEIVED","duplicate":false}
```

Repetir el mismo id con el mismo body devuelve el evento existente:

```bash
./scripts/send-event.sh demo-1
# {"id":"f0f0e4b8-...","status":"RECEIVED","duplicate":true}
```

Cambiar el body con el mismo id produce un conflicto:

```bash
EVENT_ID=demo-1 BODY='{"type":"demo.ping","data":{"id":"demo-1","v":2}}' ./scripts/send-event.sh
# 409 IDEMPOTENCY_CONFLICT
```

### Demo de duplicados

```bash
curl -s -X POST 'http://localhost:8080/v1/demo/burst?count=100&duplicates=10' | jq
```

```json
{"requested":110,"accepted":100,"duplicates":10,"conflicts":0,"durationMs":420}
```

### Observar

```bash
curl -s localhost:8080/v1/stats | jq
curl -s localhost:8080/actuator/health | jq
curl -s localhost:8080/actuator/prometheus | grep hookledger
```

### Tests

No necesitas Docker: los tests levantan PostgreSQL embebido.

```bash
mvn verify
```

## API

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/v1/events` | Ingesta firmada e idempotente |
| `GET` | `/v1/events/{id}` | Estado de un evento |
| `GET` | `/v1/stats` | Conteos por estado y backlog del outbox |
| `POST` | `/v1/demo/burst?count=100&duplicates=10` | Genera eventos de demo |
| `GET` | `/actuator/health` | Incluye `outbox.pending` y `outbox.oldestAgeSeconds` |
| `GET` | `/actuator/prometheus` | Métricas Micrometer |

### Headers de ingesta

| Header | Requerido | Ejemplo |
|---|---|---|
| `X-Hook-Source` | Sí | `demo` |
| `X-Hook-Id` | Sí | `evt_01J...` (id estable del proveedor) |
| `X-Hook-Timestamp` | Sí | epoch seconds |
| `X-Hook-Signature` | Sí | HMAC-SHA256 hex de `timestamp + "." + body` |
| `X-Hook-Type` | No | `payment.approved` (si falta, se usa `body.type`) |

La firma se compara con `MessageDigest.isEqual` (tiempo constante) y el timestamp debe estar dentro
de `MAX_CLOCK_SKEW_SECONDS` (300 por defecto).

## Decisiones de diseño

- [ADR 0001 — Outbox transaccional](docs/adr/0001-transactional-outbox.md)
- [ADR 0002 — Idempotencia en la base de datos](docs/adr/0002-idempotency-at-the-database.md)
- [ADR 0003 — HMAC con ventana de timestamp](docs/adr/0003-hmac-timestamp-window.md)

Otras decisiones deliberadas:

- **SQS, no Kafka (todavía).** A decenas de eventos por segundo, Kafka agrega operación sin beneficio.
  El día que haga falta orden por partición o replay masivo, el seam `EventPublisher` permite cambiarlo.
- **At-least-once, no “exactly-once”.** El consumidor debe ser idempotente; el outbox puede duplicar
  si el proceso muere entre publicar y hacer commit. Eso se documenta y se prueba, no se esconde.
- **Sin UI en la Semana 1.** Primero la corrección; la página de demo y el dashboard llegan cuando
  los números existan.

## Evidencia

| Claim | Cómo se reproduce | Resultado |
|---|---|---|
| Suite verde | `mvn -B -ntp test` | 14 tests, 0 fallos (2026-10-26) |
| Duplicados concurrentes | `HookledgerIntegrationTest.concurrentDuplicatesCreateOneEventAndOneOutboxRow` | 16 requests → 1 evento, 1 outbox |
| Outbox sin doble claim | `HookledgerIntegrationTest.outboxPublisherClaimsEachRowExactlyOnceUnderConcurrency` | 100 filas, 2 publishers → `attempts = 1` en todas |
| Demo con duplicados | `HookledgerIntegrationTest.demoBurstReportsAcceptedAndDuplicateEvents` | 20 únicos + 5 duplicados → 20 eventos |
| Firma inválida | `HookledgerIntegrationTest.invalidSignatureIsRejectedBeforePersisting` | `401`, 0 filas escritas |

El detalle y las fechas viven en [docs/evidence](docs/evidence/README.md).

## Roadmap

- [x] Semana 1 — ingesta HMAC, idempotencia, outbox, tests, CI.
- [ ] Semana 2 — SQS + DLQ, worker con retries/backoff, replay, Terraform, k3s, GHCR.
- [ ] Semana 3 — k6 10k eventos, chaos (worker kill, duplicados, veneno), Grafana, alertas SLO, demo pública.
- [ ] Post-MVP — migrar el webhook de MercadoPago de `erp-liveflow` a este servicio.

## Seguridad

- El secreto de cada fuente se configura por entorno; no hay secretos en el repo.
- `DEMO_ENABLED=true` y el secreto `dev-secret` son **solo para desarrollo**. En producción:
  `DEMO_ENABLED=false` y un secreto aleatorio por fuente.
- El endpoint de demo no está rate-limitado todavía; no lo expongas a Internet sin límites.
- La firma cubre el body crudo, no el JSON re-serializado.

## Licencia

MIT. Ver [LICENSE](LICENSE).
