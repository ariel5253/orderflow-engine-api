---
id: ADR-0001
title: Sealed quotes and a single-transaction confirmation
status: proposed
lang: en
pair: 0001-order-pricing-and-confirmation.es.md
last-reviewed: 2026-10-14
---

# ADR-0001: Sealed quotes and a single-transaction confirmation

| Status | Date | Decision makers | Confidence |
|--------|------|-----------------|------------|
| proposed | 2026-10-14 | Tech Lead | High. Medium on gift tax (depends on the country) |

Confirmed orders went missing in three releases. We must guarantee confirmed total = quoted total, exact money, that a 201 is a stored order, and that its effects reach push and the ERP.

## Decisions and discarded alternatives

| Decision | Discarded | Why |
|----------|-----------|-----|
| Insert-only quote lines. The order references them, with no amounts | Copying the breakdown. Recomputing at confirmation | Two copies diverge. Recomputing changes with prices |
| `BigDecimal` per line. Combos, then tiers, then tax | `long`. Rounding the total. Tiers first | Hides cents. Double discount on the combo |
| One PostgreSQL transaction: key → quote → credit → order → outbox; 201 after commit. Outbox with a lease and `event_id` | Asynchronous write or saga. Publishing after commit. Memory | The "confirmed but missing" defect. Lost events. No real locks |
| Hexagonal monolith, separate repositories (API, docs, web, infra, tools) | Microservices. Monorepo | One transaction covers the write. Each component evolves apart |

## Not implemented, on purpose

- **Authentication:** out of scope. A gateway would inject the tenant and customer.
- **Revocation, cancellation and credit release:** not asked for. Revocation is the mitigation.
- **Real push and ERP clients:** fakes behind ports already prove the guarantees.
- **Catalog, tax categories, price history, OpenAPI and deployment:** not asked for.

## Code review checklist

- No `float` or `double`. Money is compared with `compareTo`.
- `@Transactional` only on use cases, with no self-invocation and no remote calls.
- Locks key → quote → credit. The 201 after commit. No cached failure.
- Every lever with a golden test seen failing. User text only in i18n.

## Risk and mitigation

**Risk:** a sealed quote honors a wrong price until it expires. **Mitigation:** a 15-minute TTL; then revocation (OPEN → REVOKED, 409). **Revisit** if an incident affects more than 100 quotes or the TTL exceeds one hour.
