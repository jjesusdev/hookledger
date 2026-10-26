# ADR 0002 — Idempotencia en la base de datos

- **Estado:** aceptado
- **Fecha:** 2026-10-26
- **Contexto:** Semana 1 de `hookledger`

## Contexto

Los proveedores reintentan webhooks: por timeout, por 5xx, por red. También pueden entregar el mismo
evento en paralelo. Un `SELECT` seguido de `INSERT` no es idempotente: dos requests pueden leer
“no existe” antes de que cualquiera escriba, y ambos procesan el evento.

## Decisión

- Clave de idempotencia: `(source, external_id)`, donde `external_id` es el id estable del proveedor
  (`X-Hook-Id`, `X-GitHub-Delivery`, etc.).
- Restricción `UNIQUE (source, external_id)` en PostgreSQL.
- Inserción con `INSERT ... ON CONFLICT (source, external_id) DO NOTHING`.
- Si la inserción devuelve 0 filas, se carga el evento existente y se compara el hash SHA-256 del
  body crudo:
  - mismo hash → `200` con `duplicate: true` (idempotente);
  - hash distinto → `409 IDEMPOTENCY_CONFLICT` (el proveedor reutilizó un id con otro contenido).
- El outbox solo se inserta cuando la fila de evento se insertó realmente.

## Consecuencias

- La base de datos es el árbitro; no hay ventana de carrera entre instancias.
- El `external_id` debe ser estable. Si un proveedor no lo envía, hay que derivarlo de un campo
  inmutable del payload y documentarlo.
- Se compara el body crudo: dos payloads semánticamente iguales pero con distinto formato (orden de
  claves, espacios) con el mismo id se consideran conflicto. Es intencional: el proveedor debe ser
  determinista.
- No hay TTL todavía. La retención se definirá junto con la política de archivado del outbox.

## Alternativas consideradas

1. **Redis `SETNX`.** Rápido, pero agrega estado que se pierde o se desincroniza con PostgreSQL.
2. **`SELECT` + `INSERT` en aplicación.** Es exactamente el bug que este ADR evita.
3. **Advisory locks.** Serializan por clave, pero no persisten la decisión y complican la operación.

## Referencias

- Implementación: `EventRepository.insertIfAbsent`, `IngestService`.
- Pruebas: `duplicateWithSamePayloadIsIdempotentAndDoesNotAppendToOutbox`,
  `duplicateWithDifferentPayloadConflicts`, `concurrentDuplicatesCreateOneEventAndOneOutboxRow`.
