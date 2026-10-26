# ADR 0003 — HMAC con ventana de timestamp

- **Estado:** aceptado
- **Fecha:** 2026-10-26
- **Contexto:** Semana 1 de `hookledger`

## Contexto

Una firma HMAC prueba que el emisor conoce el secreto, pero no impide que un atacante que capture
una request válida la reenvíe más tarde. Los proveedores serios incluyen un timestamp firmado para
limitar esa ventana.

## Decisión

- La firma es `HMAC-SHA256(secret, timestamp + "." + raw_body)`, hex en `X-Hook-Signature`.
- `timestamp` viaja en `X-Hook-Timestamp` como epoch seconds y **está cubierto por la firma**.
- Se rechaza si `|now - timestamp| > maxClockSkewSeconds` (300 s por defecto).
- La comparación usa `MessageDigest.isEqual` para evitar filtraciones por tiempo.
- El body se lee como bytes crudos; no se re-serializa antes de verificar.

## Consecuencias

- El emisor y el receptor necesitan relojes razonablemente sincronizados (NTP).
- Una request capturada puede reenviarse dentro de la ventana, pero el `external_id` la deduplica;
  fuera de la ventana se rechaza por timestamp.
- El secreto debe rotarse de forma coordinada con el proveedor.

## Alternativas consideradas

1. **Solo HMAC sin timestamp.** Permite replay indefinido de una request capturada.
2. **Nonce store.** Más estado y expiración; útil si el proveedor no firma timestamp.
3. **mTLS o allowlist de IPs.** Depende del proveedor y no cubre el contenido.

## Referencias

- Implementación: `Hmac`, `HmacVerifier`.
- Pruebas: `HmacTest` (vector conocido, body alterado, timestamp expirado, firma malformada).
