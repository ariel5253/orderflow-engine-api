# orderflow-engine-api

[Español](README.es.md)

Order pricing engine and idempotent order confirmation for a multi-country B2B beverage marketplace. Java 21 · Spring Boot · PostgreSQL.

> **Status:** the specification is complete and the implementation is in progress. The verified state of each task is in `handoff/HANDOFF.md` of the `orderflow-engine-docs` repository.

## How to run

| Need | Version |
|------|---------|
| JDK | 21 |
| Docker | running, because the tests start PostgreSQL with Testcontainers |

```bash
./mvnw verify
```

This **single command** builds the service and runs every test: unit, golden, integration, contract and architecture. It also enforces the coverage gate (≥ 90% on `pricing`).

To run the service with its own PostgreSQL, from this repository alone:

```bash
docker compose up --build
```

Health: `http://localhost:8080/actuator/health`. Configuration comes from environment variables. `.env.example` lists every key with a comment. Copy it to `.env` only to change a value. `.env` is never committed.

Mutation testing is optional and slower: `./mvnw -Pmutation verify` runs PIT on the pricing domain and requires that at least 80% of the injected faults are detected.

## Lever order

| Step | Lever | Why this position |
|------|-------|-------------------|
| 1 | Gift eligibility, on paid quantities | A gift is triggered by what is bought and never reduces what is paid for product |
| 2 | Combos | A negotiated bundle price consumes its units first, so they never get a tier discount too |
| 3 | Tier discounts, marginal brackets, on units no combo consumed | The generic volume incentive applies only to what the bundle did not price |
| 4 | Line net = gross − discounts | Commercial discounts are settled before tax |
| 5 | Taxable base (net, plus the gift reference value when the country taxes gifts) | Tax applies to the base after commercial discounts |
| 6 | Tax per line, at each SKU's rate | Rates are per SKU |
| 7 | Total = Σ net + taxes | It needs every previous step |
| 8 | Credit check | It needs the final total. Advisory at quote time, enforced at confirmation |

**Tier brackets.** The assignment says the brackets are "acumulativas por tramos". We read that as marginal brackets: each percentage applies only to the units inside its bracket. Example (golden test 1): 25 units at 2,500, with 10–19 → 5% and 20+ → 10%, give 1,250 + 1,500 = **2,750** of discount.

A combo appears as **one** discount entry (`COMBO:<id>`). Its products never show loose discounts. Full rules: SPEC §3 in the `orderflow-engine-docs` repository.

## Rounding policy

- **Type:** `Money` wraps `BigDecimal` and a currency policy. `float` and `double` are banned from the domain by an architecture test.
- **Scale and mode** come from the country data. COP has scale 0, USD has scale 2, and the mode is `HALF_UP`. They are never hard-coded and never taken from `java.util.Currency`.
- **Where rounding happens: per line only.**
  - Each discount component is rounded per line. A combo is split across its lines by largest remainder, so the parts add up exactly.
  - Tax is rounded per line.
  - Totals are sums of rounded lines and are **never re-rounded**.
- **Why per line:** an electronic invoice carries tax per line, so the order and its invoice always match.

## Consistency model

- **Quote = confirmation.** A quote's lines and discounts are insert-only. The order references the quote and stores no amount, so there is one copy of every figure. Nothing is copied or recomputed, which means later price or lever changes cannot reach the order.
- **One ACID transaction per confirmation.** It covers the idempotency key, the quote lock, the credit consumption, the order and the outbox events, with locks always taken in that order.
- **The 201 is sent only after the commit.** Order detail and history read the same primary tables. A returned order number therefore always points to a committed, readable row.
- **Retries:**
  - The same `Idempotency-Key` with the same body replays the stored response.
  - The same key with a different body → 422.
  - Failures are not cached.
- **External effects.** Push and ERP leave only through a transactional outbox, with at-least-once delivery, backoff and dead-lettering. Consumers deduplicate by `event_id`. If a system is down, the order stays confirmed and the effect waits.
- **The model:** strong consistency inside the database, eventual consistency for push and the ERP.
- **What is sacrificed:**
  - availability while the database is down
  - per-customer serialization on the credit row
  - a short delay before push and ERP

## Golden tests

Every golden test is tagged `@Tag("golden")` and asserts the full breakdown.

| Assignment case | Test |
|-----------------|------|
| 1. Discount in two brackets | `GoldenPricingTest#gt01` |
| 2. Combo without loose discounts | `GoldenPricingTest#gt02` |
| 3. Gift with its quantity | `GoldenPricingTest#gt03` |
| 4. Combo with taxes | `GoldenPricingTest#gt04` |
| 5. Credit order over the limit | `GoldenQuoteTest#gt05`, `CreditLimitIT` |
| 6. Quote and confirm give the same total | `QuoteConfirmRegressionIT` |

## ADR

[ADR-0001: Sealed quotes and a single-transaction confirmation](docs/adr/0001-order-pricing-and-confirmation.md). It covers the decisions and discarded alternatives, what was not implemented on purpose, the code review checklist, and the main risk with its mitigation.

## Not included

| Item | Why |
|------|-----|
| Authentication | Out of scope. Tenant and customer come from headers |
| Quote revocation, cancellation, credit release | Not required. Revocation is the ADR's risk mitigation |
| Real push and ERP clients | Fakes behind ports prove the delivery guarantees |
| SonarQube or SonarCloud | It needs a server. It imports the JaCoCo XML report this build already produces, so it can be added in CI without project changes |
| OpenAPI file, deployment | Not required. The contract is `spec/api-contract.md` in the `orderflow-engine-docs` repository |

## Repositories

| Repository | Role |
|------------|------|
| `orderflow-engine-api` (this one) | The deliverable: code, tests, this README, the ADR |
| `orderflow-engine-docs` | Specification, contract, data model, architecture, diagrams |
| `orderflow-engine-web` | The optional order-summary screen |
| `orderflow-engine-infra` | The full stack with Docker Compose, and the workspace test command |
| `orderflow-engine-tools` | Generators and checks for the documentation and the repositories |
| `orderflow-engine-app` | Reserved for a future mobile app |
