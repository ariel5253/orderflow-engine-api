---
id: ADR-0001
title: Cotizaciones selladas y confirmación en una sola transacción
status: proposed
lang: es
pair: 0001-order-pricing-and-confirmation.md
last-reviewed: 2026-10-14
---

# ADR-0001: Cotizaciones selladas y confirmación en una sola transacción

| Estado | Fecha | Decisores | Confianza |
|--------|-------|-----------|-----------|
| propuesto | 2026-10-14 | Tech Lead | Alta. Media en el impuesto del obsequio (depende del país) |

En tres releases se perdieron pedidos confirmados. Hay que garantizar total confirmado = cotizado, dinero exacto, que un 201 sea un pedido guardado y que sus efectos lleguen a push y ERP.

## Decisiones y alternativas descartadas

| Decisión | Descartado | Por qué |
|----------|------------|---------|
| Líneas de cotización de solo inserción. El pedido las referencia sin montos | Copiar el desglose. Recalcular al confirmar | Dos copias divergen. Recalcular cambia con los precios |
| `BigDecimal` por línea. Combos, luego escalas, luego impuestos | `long`. Redondear el total. Escalas primero | Oculta centavos. Doble descuento en el combo |
| Una transacción PostgreSQL: clave → cotización → cupo → pedido → outbox; 201 tras el commit. Outbox con lease y `event_id` | Escritura asíncrona o saga. Publicar tras el commit. Memoria | El defecto "confirmado pero inexistente". Pierde eventos. Sin bloqueos reales |
| Monolito hexagonal, repositorios separados (API, docs, web, infra, tools) | Microservicios. Monorepo | Una transacción cubre la escritura. Cada componente evoluciona aparte |

## No implementado, a propósito

- **Autenticación:** fuera de alcance. Un gateway inyectaría el tenant y el cliente.
- **Revocación, cancelación y liberación de cupo:** no se pidieron. La revocación es la mitigación.
- **Push y ERP reales:** los fakes detrás de puertos prueban las garantías.
- **Catálogo, categorías tributarias, historial de precios, OpenAPI y despliegue:** no se pidieron.

## Qué revisaría en un code review

- Nada de `float` ni `double`. El dinero se compara con `compareTo`.
- `@Transactional` solo en casos de uso, sin autoinvocación ni llamadas remotas.
- Bloqueos clave → cotización → cupo. El 201 tras el commit. Ningún fallo cacheado.
- Cada palanca con un golden test visto fallar. Textos de usuario solo en i18n.

## Riesgo y mitigación

**Riesgo:** una cotización sellada respeta un precio erróneo hasta vencer. **Mitigación:** TTL de 15 minutos; luego, revocación (OPEN → REVOKED, 409). **Revisar** si un incidente afecta a más de 100 cotizaciones o el TTL supera una hora.
