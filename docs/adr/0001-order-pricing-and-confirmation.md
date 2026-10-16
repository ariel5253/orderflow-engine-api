---
id: ADR-0001
title: Sealed quotes and a single-transaction confirmation
status: accepted
lang: en
pair: 0001-order-pricing-and-confirmation.es.md
last-reviewed: 2026-10-15
---

# ADR-0001: Sealed quotes and a single-transaction confirmation

| Status | Date | Decision makers | Confidence |
|--------|------|-----------------|------------|
| accepted | 2026-10-15 | Tech Lead | High. Medium on gift tax |

Confirmed orders went missing in three releases. We must guarantee confirmed total = quoted total, exact money, that a 201 is a stored order, and that effects on push and the ERP are never lost or applied twice.

## Decisions and discarded alternatives

| Decision | Discarded | Why |
|----------|-----------|-----|
| Sealed, insert-only quote. The order references it, with no amounts | Copying the breakdown. Recomputing on confirm | Copies diverge. Prices change |
| `BigDecimal` rounded per line, totals never re-rounded. Combos before tiers | `long` minor units. Rounding the total. Tiers first | Awkward with tax rates. Invoice mismatch. Double discount |
| One PostgreSQL ACID transaction, locks key → quote → credit. The 201 after commit | In-memory store. Asynchronous write or saga. Keys in a cache | No real locks. The "confirmed but missing" defect. Not atomic |
| Transactional outbox with lease and backoff. Push and ERP deduplicate by `event_id` | Publishing after commit. Two-phase commit | Lost events. Push and ERP cannot join it |
| Hexagonal monolith, explicit SQL, separate repositories | Microservices. JPA. Monorepo | One transaction. JPA hides locks. Components evolve apart |

## Not implemented, on purpose

- **Authentication:** out of scope. A gateway would inject tenant and customer.
- **Real push and ERP clients:** fakes behind ports prove the guarantees.
- **Not asked for:** revocation (the risk mitigation), cancellation, credit release, catalog, tax categories, price history, OpenAPI, deployment, CI and SonarQube (README).

## Code review checklist

- No `float` or `double`. Money compared with `compareTo`. No UPDATE on quotes.
- `@Transactional` only on use cases, with no self-invocation and no remote calls.
- Locks key → quote → credit. The 201 after commit. No cached failure.
- The relay sends outside any transaction. Only the lease holder marks the outcome.
- Every lever with a golden test seen failing. User text only in i18n.

## Risk and mitigation

**Risk:** a sealed quote honors a wrong price until it expires. **Mitigation:** a 15-minute TTL per country; then revocation (OPEN → REVOKED, 409). **Revisit** if an incident affects more than 100 quotes or the TTL exceeds one hour.
