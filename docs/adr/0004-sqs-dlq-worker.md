# ADR 0004 — SQS con DLQ y worker separado

- **Estado:** aceptado
- **Fecha:** 2026-10-26
- **Contexto:** Semana 2 de `hookledger`

## Contexto

El outbox resuelve el dual-write entre PostgreSQL y el transporte, pero todavía falta demostrar que un
evento llega a un consumidor real y que un fallo del consumidor no lo pierde. También falta una forma
de reintentar mensajes que agotaron sus intentos.

## Decisión

1. **SQS estándar, no FIFO.** El orden por `external_id` no es un requisito del producto y FIFO reduce
   throughput y agrega costo operativo. Si un consumidor necesita orden, lo resuelve por clave.
2. **Publisher detrás de `EventPublisher`.** `SqsEventPublisher` se activa con
   `hookledger.publisher=sqs`; el outbox no sabe qué transporte usa.
3. **Worker separado, misma imagen.** `WORKER_ENABLED=true` activa un consumidor que hace long polling,
   entrega por HTTP firmado y borra el mensaje solo con 2xx. Se puede escalar sin escalar la API.
4. **Retries con visibilidad, no con un broker de reintentos.** Al fallar, el worker cambia la
   visibilidad con backoff exponencial y deja el mensaje en la cola. Tras `maxReceiveCount`, SQS lo
   mueve a la DLQ.
5. **DLQ con retención de 14 días.** La DLQ es el registro de mensajes venenosos; no se borra
   automáticamente.
6. **Replay explícito y autenticado.** `POST /v1/admin/replay` mueve mensajes de la DLQ a la cola
   principal. Requiere `ADMIN_TOKEN`; sin token el endpoint no existe.
7. **Intentos persistidos.** `delivery_attempt` guarda `(event_id, attempt)`, código HTTP, outcome y
   error. La unique constraint absorbe redeliveries con el mismo número de intento.

## Consecuencias

- La entrega sigue siendo **at-least-once**. Un fallo después de entregar y antes de borrar el
  mensaje produce una segunda entrega; el target debe deduplicar por `X-Hook-Id`.
- `WORKER_MAX_RECEIVES` debe coincidir con `maxReceiveCount` de Terraform. Si no, el evento puede
  marcarse `FAILED` antes o después de llegar a la DLQ.
- El tamaño máximo de body baja a 200 000 bytes para dejar espacio al envelope y no superar el límite
  de 256 KiB de SQS. Un payload mayor requiere claim check en S3.
- La DLQ no se drena sola. Alerta `dlq-not-empty` en CloudWatch y replay manual.
- El worker usa `java.net.http.HttpClient`; no agrega un cliente HTTP extra.
- LocalStack permite probar el camino completo en CI sin credenciales de AWS.

## Alternativas consideradas

1. **Kafka.** Mejor para orden y replay masivo, pero sobredimensionado para decenas de eventos por
   segundo y mucho más caro de operar.
2. **SNS + SQS.** Útil para fan-out a varios consumidores; todavía no hay varios consumidores.
3. **Retry en la base de datos en vez de SQS.** Convierte la tabla de outbox en un scheduler de
   entregas y pierde las ventajas de visibilidad y DLQ del broker.
4. **Exactly-once.** No existe de forma práctica entre HTTP y SQS; prometerlo sería deshonesto.

## Referencias

- Implementación: `SqsEventPublisher`, `SqsWorker`, `DeliveryService`, `DeliveryRepository`,
  `ReplayService`.
- Infraestructura: `infra/terraform/main.tf`.
- Pruebas: `SqsEventPublisherTest`, `SqsWorkerTest`, `DeliveryServiceTest`, `ReplayServiceTest`, job
  `sqs-smoke` en CI.
