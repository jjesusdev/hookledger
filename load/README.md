# Carga — ingestión de webhooks

Prueba de llegada constante contra `POST /v1/events`. Genera eventos firmados con ids únicos, mide la
latencia de la API y verifica que el outbox drene después.

## Correr localmente

```bash
docker compose up -d --build
# esperar /actuator/health = UP

docker run --rm --network host -v "$PWD/load:/load:ro" \
  -e BASE_URL=http://localhost:8080 \
  -e RATE=50 -e DURATION=30s \
  grafana/k6:2.3.0 run /load/ingest.js
```

Contra el stack con SQS:

```bash
docker compose -f docker-compose.yml -f docker-compose.sqs.yml up -d --build
docker run --rm --network host -v "$PWD/load:/load:ro" \
  -e BASE_URL=http://localhost:8080 -e RATE=20 -e DURATION=30s \
  grafana/k6:2.3.0 run /load/ingest.js
```

## Variables

| Variable | Default | Uso |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | destino |
| `DEMO_WEBHOOK_SECRET` | `dev-secret` | secreto de la fuente `demo` |
| `RATE` | `50` | requests por segundo |
| `DURATION` | `30s` | duración del escenario |

## Umbrales

| Métrica | Umbral | Por qué |
|---|---|---|
| `http_req_failed` | `< 1%` | un error de firma o de base es un fallo, no ruido |
| `http_req_duration{p95}` | `< 50 ms` | la ingesta es una escritura corta, no un batch |
| `http_req_duration{p99}` | `< 200 ms` | absorbe picos sin ocultar colas |
| `hookledger_ingest_latency{p95}` | `< 50 ms` | misma señal, aislada del resto de requests |

En la corrida base (50 rps, 30 s, runner de 4 vCPU) el p95 fue **3,06 ms** y el máximo **45,88 ms**;
k6 no imprime el p99 en el resumen, pero el umbral lo evalúa. Los umbrales dejan margen para variación
del runner sin aceptar una regresión de un orden de magnitud.

## Qué mide y qué no

- **Mide** la ruta `HMAC → validación → INSERT webhook_event + outbox_event` y el drenaje del outbox.
- **No mide** la entrega al target ni SQS: para eso están `sqs-smoke`, `dlq-replay-smoke` y
  `worker-down-smoke`.
- El número es del runner de CI (4 vCPU compartidas), no de producción. Sirve como línea base
  reproducible, no como promesa de throughput.
