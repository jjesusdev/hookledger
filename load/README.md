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
| `http_req_duration{p95}` | `< 500 ms` | detecta una regresión catastrófica, no un SLO fino |
| `http_req_duration{p99}` | `< 1000 ms` | absorbe la variación de runners compartidos |
| `hookledger_ingest_latency{p95}` | `< 500 ms` | misma señal, aislada del resto de requests |

Dos corridas del mismo test a 50 rps dieron p95 de **3,06 ms** y **188,85 ms**; la mediana se mantuvo
en ~1,7 ms en ambas. La cola depende de cómo el runner compartido agenda CPU, no solo del servicio. Por
eso el umbral es ancho: el job garantiza 0 errores, outbox drenado y ausencia de latencia desbocada; los
números reales se reportan en la evidencia, no se esconden detrás de un umbral.

## Qué mide y qué no

- **Mide** la ruta `HMAC → validación → INSERT webhook_event + outbox_event` y el drenaje del outbox.
- **No mide** la entrega al target ni SQS: para eso están `sqs-smoke`, `dlq-replay-smoke` y
  `worker-down-smoke`.
- El número es del runner de CI (4 vCPU compartidas), no de producción. Sirve como línea base
  reproducible, no como promesa de throughput.
