# hookledger

[![ci](https://github.com/jjesusdev/hookledger/actions/workflows/ci.yml/badge.svg)](https://github.com/jjesusdev/hookledger/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

Plataforma de ingesta y entrega confiable de webhooks: valida la firma, deduplica, persiste y
publica por un outbox transaccional. Diseñada para el caso aburrido y caro: el proveedor reintenta,
el consumidor se cae y nadie sabe qué evento se perdió.

> **Estado: Semana 3 en curso.** Ingesta HMAC, idempotencia atómica, outbox transaccional, publisher
> SQS, worker con retries/backoff, DLQ, replay admin, Terraform validado, Prometheus + Grafana con
> alertas SLO y prueba de carga k6. El camino completo está probado contra LocalStack en CI. k3s y la
> demo pública siguen pendientes.

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

## Qué hace hoy (Semana 3)

- `POST /v1/events` con firma HMAC-SHA256 sobre `timestamp + "." + body`.
- Ventana de reloj configurable (5 minutos por defecto) para limitar replay.
- Idempotencia atómica: `UNIQUE (source, external_id)` + `ON CONFLICT DO NOTHING`.
- Mismo evento y mismo payload → `200` con `duplicate: true`.
- Mismo evento y payload distinto → `409 IDEMPOTENCY_CONFLICT`.
- `webhook_event` y `outbox_event` se escriben en **la misma transacción**.
- Poller de outbox con `FOR UPDATE SKIP LOCKED` (seguro con múltiples publishers).
- Publisher SQS conmutable por configuración (`hookledger.publisher=sqs`).
- Worker que consume SQS, entrega por HTTP firmado y reintenta con backoff exponencial.
- DLQ nativa de SQS después de 3 intentos; replay autenticado de la DLQ a la cola principal.
- Intentos de entrega persistidos y consultables por evento.
- Métricas de ingesta, outbox y entrega; salud del backlog en `/actuator/health`.
- Prometheus + Grafana con dashboard provisionado y cinco alertas SLO.
- Terraform para colas, DLQ, política IAM y alarmas de CloudWatch (validado, no aplicado).
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
                        EventPublisher ──► SQS ──► worker
                                                │
                                     HTTP firmado al target
                                                │
                                        DELIVERED / retry
                                                │
                                     3 fallos ──► DLQ
                                                │
                                     replay admin ──► SQS
```

**Por qué outbox y no publicar dentro del request:** si publicas a la cola y después falla el commit,
el mensaje existe pero el evento no. Si haces commit y después publicas, el evento existe pero el
mensaje no. El outbox elimina el dual-write: PostgreSQL es la fuente de verdad y la cola es derivada.

**Por qué `ON CONFLICT` y no `findUnique` + `update`:** dos entregas concurrentes del mismo webhook
pueden pasar el `SELECT` antes de que cualquiera escriba. La base de datos decide el ganador.

**Por qué el worker no borra el mensaje al fallar:** SQS lo vuelve a entregar y, al superar
`maxReceiveCount`, lo mueve a la DLQ. El mensaje no se pierde ni se descarta silenciosamente.

## Quick start

### Docker Compose

```bash
git clone https://github.com/jjesusdev/hookledger.git
cd hookledger
docker compose up --build
```

Ese modo usa el publisher de log: sirve para probar ingesta e idempotencia sin cola.

### Camino completo con SQS (LocalStack)

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml up --build -d
id="$(./scripts/send-event.sh sqs-1 | jq -r .id)"
sleep 3
curl -s "localhost:8080/v1/events/${id}" | jq
curl -s "localhost:8080/v1/events/${id}/deliveries" | jq
```

Salida esperada: `status: "DELIVERED"` y un intento con `outcome: "DELIVERED"`. LocalStack crea la
cola principal y la DLQ con `maxReceiveCount=3`; el worker entrega al receiver de demo con una firma
propia del target.

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml down -v
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

Dashboard local, con Prometheus y Grafana:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
# Grafana:    http://localhost:3000  (admin / admin)
# Prometheus: http://localhost:9090
```

El dashboard `hookledger-overview` se aprovisiona desde el repo: ingesta por resultado, entregas,
replays, backlog y edad del outbox, p95/p99 de ingesta, 5xx y heap. Las alertas viven en
`observability/prometheus/alerts.yml`; se validan con `promtool` en el job `observability-smoke`.

### Tests

No necesitas Docker: los tests levantan PostgreSQL embebido.

```bash
mvn verify
```

### Kubernetes (k3s)

Los manifiestos están en `deploy/k8s`: 2 réplicas de la API, worker separado, probes, PDB, PostgreSQL
con PVC y un Secret de plantilla. Se renderizan y validan en CI; **no hay clúster desplegado**.

```bash
kubectl kustomize deploy/k8s > /tmp/hookledger.yaml
docker run --rm -v /tmp:/tmp ghcr.io/yannh/kubeconform:v0.8.0 \
  -strict -summary -kubernetes-version 1.32.0 /tmp/hookledger.yaml
```

El detalle (Secret real, IRSA, límites del PostgreSQL in-cluster) está en
[deploy/k8s/README.md](deploy/k8s/README.md).

## API

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/v1/events` | Ingesta firmada e idempotente |
| `GET` | `/v1/events/{id}` | Estado de un evento |
| `GET` | `/v1/events/{id}/deliveries` | Intentos de entrega, con código HTTP y error |
| `GET` | `/v1/stats` | Conteos por estado, backlog e intentos de entrega |
| `POST` | `/v1/demo/burst?count=100&duplicates=10` | Genera eventos de demo |
| `POST` | `/v1/demo/receive` | Target de demo; valida la firma del worker |
| `POST` | `/v1/admin/replay?max=10` | Mueve mensajes de la DLQ a la cola principal |
| `GET` | `/actuator/health` | Incluye `outbox.pending` y `outbox.oldestAgeSeconds` |
| `GET` | `/actuator/prometheus` | Métricas Micrometer |

El endpoint de replay exige `X-Admin-Token` y solo existe si `ADMIN_TOKEN` está configurado. No está
pensado para exponerse sin autenticación fuerte; en la demo usa un token aleatorio.

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

### Configuración de entrega

| Variable | Default | Uso |
|---|---|---|
| `PUBLISHER` | `log` | `log` o `sqs` |
| `SQS_QUEUE_URL` | vacío | cola principal; obligatoria con `PUBLISHER=sqs` o worker activo |
| `SQS_DLQ_URL` | vacío | DLQ para replay |
| `AWS_REGION` | `us-east-1` | región de SQS |
| `AWS_ENDPOINT_URL` | vacío | override para LocalStack |
| `WORKER_ENABLED` | `false` | activa el consumidor |
| `WORKER_MAX_RECEIVES` | `3` | debe coincidir con `maxReceiveCount` de la cola |
| `DEMO_TARGET_URL` | `http://localhost:8080/v1/demo/receive` | target del worker en la demo |
| `DEMO_TARGET_SECRET` | `dev-target-secret` | secreto con el que el worker firma al target |
| `ADMIN_TOKEN` | vacío | habilita `/v1/admin/replay`; vacío = endpoint ausente |
| `MAX_BODY_BYTES` | `200000` | límite por debajo del máximo de 256 KiB de SQS |

Los secretos reales se inyectan por variables de entorno o un secret manager; no hay secretos en el
repo.

## Decisiones de diseño

- [ADR 0001 — Outbox transaccional](docs/adr/0001-transactional-outbox.md)
- [ADR 0002 — Idempotencia en la base de datos](docs/adr/0002-idempotency-at-the-database.md)
- [ADR 0003 — HMAC con ventana de timestamp](docs/adr/0003-hmac-timestamp-window.md)
- [ADR 0004 — SQS con DLQ y worker separado](docs/adr/0004-sqs-dlq-worker.md)

Otras decisiones deliberadas:

- **SQS, no Kafka (todavía).** A decenas de eventos por segundo, Kafka agrega operación sin beneficio.
  El día que haga falta orden por partición o replay masivo, el seam `EventPublisher` permite cambiarlo.
- **At-least-once, no “exactly-once”.** El consumidor debe ser idempotente; el outbox puede duplicar
  si el proceso muere entre publicar y hacer commit, y SQS puede reentregar si el worker muere entre
  entregar y borrar. Eso se documenta y se prueba, no se esconde.
- **Dashboard sin stack de logging.** Prometheus + Grafana alcanzan para las señales de este servicio;
  Loki/ELK agregarían operación sin una pregunta que responder todavía.

## Evidencia

| Claim | Cómo se reproduce | Resultado |
|---|---|---|
| Suite verde | `mvn -B -ntp verify` | 33 tests, 0 fallos (2026-10-26) |
| Duplicados concurrentes | `HookledgerIntegrationTest.concurrentDuplicatesCreateOneEventAndOneOutboxRow` | 16 requests → 1 evento, 1 outbox |
| Outbox sin doble claim | `HookledgerIntegrationTest.outboxPublisherClaimsEachRowExactlyOnceUnderConcurrency` | 100 filas, 2 publishers → `attempts = 1` en todas |
| Demo con duplicados | `HookledgerIntegrationTest.demoBurstReportsAcceptedAndDuplicateEvents` | 20 únicos + 5 duplicados → 20 eventos |
| Firma inválida | `HookledgerIntegrationTest.invalidSignatureIsRejectedBeforePersisting` | `401`, 0 filas escritas |
| Stack en contenedores | job `compose-smoke` en CI | health `UP`, evento aceptado, duplicado detectado, outbox en 0 |
| SQS → worker → HTTP firmado | job `sqs-smoke` en CI | evento `DELIVERED` con un intento `DELIVERED` |
| DLQ + replay | job `dlq-replay-smoke` en CI | 3 fallos → DLQ → replay → `DELIVERED`, 4 intentos auditados |
| Worker caído | job `worker-down-smoke` en CI | evento `PUBLISHED` esperando en SQS; al volver el worker, `DELIVERED` sin pérdida |
| Carga de ingesta | job `load-smoke` en CI | 50 eventos/s por 30 s, mediana 1,7–2,7 ms, 0 fallos, outbox drenado |
| Métricas y dashboard | job `observability-smoke` en CI | `promtool test rules` verde, gauges `hookledger_*` scrapeados, dashboard servido por Grafana |
| PostgreSQL caído | job `postgres-down-smoke` en CI | health `503`, liveness `200`, ingesta `500`; reinicio → health `200` y evento aceptado |
| Manifiestos k3s | job `k8s-manifests` en CI | `kubectl kustomize` + `kubeconform -strict`: 10/10 recursos válidos |
| Terraform | job `terraform` en CI | `fmt`, `init -backend=false` y `validate` verdes |

El detalle y las fechas viven en [docs/evidence](docs/evidence/README.md).

## Roadmap

- [x] Semana 1 — ingesta HMAC, idempotencia, outbox, tests, CI.
- [x] Semana 2 — SQS + DLQ, worker con retries/backoff, replay, Terraform validado, LocalStack en CI.
- [x] Semana 2 (extra) — DLQ + replay y worker caído end-to-end en CI; la demo pública queda pendiente de hosting.
- [x] Semana 3 (parcial) — k6 a 50 eventos/s, mediana 1,7–2,7 ms y 0 fallos.
- [x] Semana 3 (parcial) — Prometheus + Grafana, dashboard provisionado y alertas SLO validadas con `promtool`.
- [x] Semana 3 (parcial) — manifiestos k3s validados con `kubeconform`; sin clúster todavía.
- [x] Semana 3 (parcial) — caos de PostgreSQL: falla cerrada y recuperación sin reiniciar la app.
- [ ] Semana 3 (resto) — GHCR, despliegue real, Alertmanager y demo pública.
- [ ] Post-MVP — migrar el webhook de MercadoPago de `erp-liveflow` a este servicio.

## Seguridad

- El secreto de cada fuente se configura por entorno; no hay secretos en el repo.
- `DEMO_ENABLED=true` y el secreto `dev-secret` son **solo para desarrollo**. En producción:
  `DEMO_ENABLED=false` y un secreto aleatorio por fuente.
- `ADMIN_TOKEN` habilita replay; si está vacío, el endpoint no se registra. Usa un token aleatorio y
  no lo expongas en el frontend.
- El endpoint de demo no está rate-limitado todavía; no lo expongas a Internet sin límites.
- La firma cubre el body crudo, no el JSON re-serializado.
- La política IAM de Terraform limita la app a las dos colas del proyecto; no usa `sqs:*` sobre `*`.

## Licencia

MIT. Ver [LICENSE](LICENSE).
