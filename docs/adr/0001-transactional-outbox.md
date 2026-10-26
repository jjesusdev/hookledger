# ADR 0001 — Outbox transaccional

- **Estado:** aceptado
- **Fecha:** 2026-10-26
- **Contexto:** Semana 1 de `hookledger`

## Contexto

La ingesta debe hacer dos cosas: guardar el evento y publicarlo para un consumidor. Publicar dentro
del request abre un dual-write: si la cola acepta el mensaje y luego falla el commit, el mensaje
existe sin evento; si el commit ocurre y falla la publicación, el evento existe sin mensaje. En
ambos casos el sistema queda inconsistente y el reintento del proveedor no siempre lo repara.

## Decisión

Persistir el evento y una fila de `outbox_event` en la **misma transacción** de PostgreSQL. Un poller
reclama filas con:

```sql
SELECT ... FROM outbox_event
WHERE published_at IS NULL
ORDER BY id
LIMIT :limit
FOR UPDATE SKIP LOCKED
```

Luego publica por la interfaz `EventPublisher` y marca la fila como publicada. `SKIP LOCKED` permite
varios publishers sin repartir la misma fila dos veces.

## Consecuencias

- La entrega es **at-least-once**: un crash entre la publicación y el commit puede duplicar un mensaje.
  El consumidor debe deduplicar. Esto se documenta y se prueba; no se promete exactly-once.
- Hay una latencia adicional igual al intervalo del poller (500 ms por defecto).
- La tabla de outbox crece; hace falta una política de retención/archivado.
- El transporte es reemplazable sin tocar la transacción de ingesta.

## Alternativas consideradas

1. **Publicar dentro del request.** Simple, pero reproduce el dual-write que este proyecto existe para evitar.
2. **Change Data Capture (Debezium).** Correcto, pero agrega Kafka Connect y operación que no se justifica
   para el volumen objetivo.
3. **Mensajería transaccional del broker.** No disponible de forma nativa en SQS; obligaría a Kafka.

## Referencias

- Implementación: `IngestService`, `OutboxRepository`, `OutboxPublisher`, `OutboxScheduler`.
- Prueba: `HookledgerIntegrationTest.outboxPublisherClaimsEachRowExactlyOnceUnderConcurrency`.
