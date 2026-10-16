---
id: ADR-0001
title: Cotizaciones selladas y confirmación en una sola transacción
status: accepted
lang: es
pair: 0001-order-pricing-and-confirmation.md
last-reviewed: 2026-10-15
---

# ADR-0001: Cotizaciones selladas y confirmación en una sola transacción

| Estado | Fecha | Decisores | Confianza |
|--------|-------|-----------|-----------|
| aceptado | 2026-10-15 | Tech Lead | Alta. Media en el impuesto del obsequio |

En tres releases se perdieron pedidos confirmados. Hay que garantizar total confirmado = cotizado, dinero exacto, que un 201 sea un pedido guardado y que los efectos en push y ERP no se pierdan ni se apliquen dos veces.

## Decisiones y alternativas descartadas

| Decisión | Descartado | Por qué |
|----------|------------|---------|
| Cotización sellada de solo inserción. El pedido la referencia sin montos | Copiar el desglose. Recalcular al confirmar | Las copias divergen. Los precios cambian |
| `BigDecimal` redondeado por línea, sin redondear totales. Combos antes que escalas | `long` en unidades mínimas. Redondear el total. Escalas primero | Incómodo con tasas. Descuadra la factura. Doble descuento |
| Una transacción ACID en PostgreSQL, bloqueos clave → cotización → cupo. El 201 tras el commit | Almacén en memoria. Escritura asíncrona o saga. Claves en caché | Sin bloqueos reales. El defecto "confirmado pero inexistente". No atómica |
| Outbox transaccional con lease y backoff. Push y ERP deduplican por `event_id` | Publicar tras el commit. Commit en dos fases | Pierde eventos. Push y ERP no entran en ella |
| Monolito hexagonal, SQL explícito, repositorios separados | Microservicios. JPA. Monorepo | Una transacción. JPA oculta bloqueos. Evolucionan aparte |

## No implementado, a propósito

- **Autenticación:** fuera de alcance. Un gateway inyectaría tenant y cliente.
- **Push y ERP reales:** los fakes detrás de puertos prueban las garantías.
- **No se pidieron:** revocación (la mitigación del riesgo), cancelación, liberación de cupo, catálogo, categorías de impuesto, historial de precios, OpenAPI, despliegue, CI y SonarQube (README).

## Qué revisaría en un code review

- Nada de `float` ni `double`. Dinero con `compareTo`. Ningún UPDATE de cotizaciones.
- `@Transactional` solo en casos de uso, sin autoinvocación ni llamadas remotas.
- Bloqueos clave → cotización → cupo. El 201 tras el commit. Ningún fallo cacheado.
- El relay envía sin transacción abierta, y solo quien tiene el lease marca el resultado.
- Cada palanca con un golden test visto fallar. Textos de usuario solo en i18n.

## Riesgo y mitigación

**Riesgo:** una cotización sellada respeta un precio erróneo hasta vencer. **Mitigación:** un TTL de 15 minutos por país; luego, revocación (OPEN → REVOKED, 409). **Revisar** si un incidente afecta a más de 100 cotizaciones o el TTL supera una hora.
