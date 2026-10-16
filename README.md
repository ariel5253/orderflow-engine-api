# orderflow-engine-api

[Español](README.es.md)

Order pricing engine and idempotent order confirmation for a multi-country B2B beverage marketplace. Java 21 · Spring Boot · PostgreSQL.

This repository stands alone: code, tests, this README and the ADR. The full specification, the API contract and the data model are in the `orderflow-engine-docs` repository.

## How to run

| Need | Version |
|------|---------|
| JDK | 21 |
| Docker | running, because the tests start PostgreSQL with Testcontainers |

```bash
./mvnw verify
```

This **single command** builds the service and runs every test: unit, golden, contract, architecture, and integration tests against a real PostgreSQL. It also enforces the coverage gate: at least 90% of the lines and branches of the `pricing` package.

To run the service with its own PostgreSQL, from this repository alone:

```bash
docker compose up --build
```

Liquibase creates the schema when the service starts and seeds two tenants: `co-alpha` (Colombia, COP, scale 0) and `ec-alpha` (Ecuador, USD, scale 2). Health: `http://localhost:8080/actuator/health`. Configuration comes from environment variables. `.env.example` lists every key with a comment. Copy it to `.env` only to change a value. `.env` is never committed.

Mutation testing is optional and slower: `./mvnw -Pmutation verify` runs PIT on `pricing.domain` and requires that at least 80% of the injected faults are detected.

### Endpoints

Every business operation needs the `X-Tenant-Id` and `X-Customer-Id` headers. `Accept-Language` is optional and picks the language of the error messages (`es` by default, or `en`). Errors are `application/problem+json` (RFC 9457) with a stable `code` and a `correlationId`. Money is a JSON string at the currency's scale. Every request, response and error is specified in `spec/api-contract.md` of the `orderflow-engine-docs` repository.

| Id | Operation | Success |
|----|-----------|---------|
| E-01 | `POST /api/v1/quotes` prices the cart and seals the quote | 201 with `Location` |
| E-02 | `GET /api/v1/quotes/{quoteId}` reads the quote and its derived status: `OPEN`, `CONFIRMED` or `EXPIRED` | 200 |
| E-03 | `POST /api/v1/orders` confirms a quote. It requires `Idempotency-Key` | 201 with `Location` |
| E-04 | `GET /api/v1/orders/{orderId}` reads an order, rendered from its quote | 200 |
| E-05 | `GET /api/v1/orders?customerId={id}&size=20` lists the customer's orders, newest first, with `size` at most 100 | 200 |

Quote 25 colas on credit for a seeded customer with a 500,000 credit limit:

```bash
curl -i http://localhost:8080/api/v1/quotes \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: co-alpha' \
  -H 'X-Customer-Id: 01a13cdb-cc00-7001-8001-000000000001' \
  -d '{"paymentMethod":"CREDIT","lines":[{"sku":"BEV-COLA-350","qty":25}]}'
```

The breakdown is golden test 1: a 2,750 tier discount, 11,353 of tax and `"total": "71103"`, with `credit.fits` true. Put the `quoteId` of the response in the `quote_id` shell variable, then confirm the quote:

```bash
curl -i http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: co-alpha' \
  -H 'X-Customer-Id: 01a13cdb-cc00-7001-8001-000000000001' \
  -H 'Idempotency-Key: 4f9b2c1e-7a3d-4e8f-9b6a-2d5c8e1f0a37' \
  -d "{\"quoteId\":\"$quote_id\"}"
```

The 201 carries the order number and the same totals as the quote. Sending the same command again returns the stored response byte for byte, with `Idempotent-Replayed: true`, and creates no second order and no second credit consumption. Another quote needs another key: the same key with a different body is rejected with 422 `IDEMPOTENCY_KEY_REUSED`. The order is in the history as soon as the 201 arrives:

```bash
curl -i 'http://localhost:8080/api/v1/orders?customerId=01a13cdb-cc00-7001-8001-000000000001' \
  -H 'X-Tenant-Id: co-alpha' \
  -H 'X-Customer-Id: 01a13cdb-cc00-7001-8001-000000000001'
```

## Lever order

| Step | Lever | Why this position |
|------|-------|-------------------|
| 1 | Gift eligibility, on paid quantities or the gross subtotal before discounts | A gift is triggered by what is bought and never reduces what is paid for product |
| 2 | Combos | A negotiated bundle price consumes its units first, so they never get a tier discount too |
| 3 | Tier discounts, marginal brackets, on units no combo consumed | The generic volume incentive applies only to what the bundle did not price |
| 4 | Line net = gross − discounts | Commercial discounts are settled before tax |
| 5 | Taxable base (net, plus the gift reference value when the country taxes gifts) | Tax applies to the base after commercial discounts |
| 6 | Tax per line, at each SKU's rate | Rates are per SKU |
| 7 | Total = Σ net + taxes | It needs every previous step |
| 8 | Credit check | It needs the final total. Advisory at quote time, enforced at confirmation |

**Tier brackets.** The assignment says the brackets are "acumulativas por tramos". We read that as marginal brackets: each percentage applies only to the units inside its bracket. Example (golden test 1): 25 units at 2,500, with 10–19 → 5% and 20+ → 10%, give 1,250 + 1,500 = **2,750** of discount. A SKU rule takes precedence over a family rule for that SKU.

**Combos.** A combo appears as **one** discount entry (`COMBO:<id>`). Its products never show loose discounts. Each participating line carries `comboAllocations`, which only derive its taxable base. A combo that would not lower the price consumes nothing.

**Gifts.** A gift is a separate line with its quantity and `GIFT:<id>` origin, priced at 0. Its reference value is taxed only where the country's gift tax policy is `TAXED`.

Full rules: SPEC §3 in the `orderflow-engine-docs` repository.

## Rounding policy

- **Type:** `Money` wraps `BigDecimal` and a currency policy. `float` and `double` are banned from the domain by an architecture test. Amounts are compared with `compareTo`.
- **Scale and mode** come from the country data. COP has scale 0, USD has scale 2, and the mode is `HALF_UP`. They are never hard-coded and never taken from `java.util.Currency`. A price with more decimals than its currency allows is rejected when it is loaded.
- **Where rounding happens: per line only.**
  - Each discount component is rounded per line. A combo is split across its lines by largest remainder, so the parts add up exactly. A component that rounds to zero is not emitted.
  - Tax is rounded per line.
  - Totals are sums of rounded lines and are **never re-rounded**.
- **Why per line:** an electronic invoice carries tax per line, so the order and its invoice always match.
- **Example (golden test 4):** a combo discount of 3,600 is split into 2,647 and 953. The taxes are 1,397 at 19% and 132 at 5%, and the total is 11,529.

## Consistency model

**Quote = confirmation.** A quote's lines and discount components are insert-only. Each line keeps the price and tax rate in force when it was quoted. The order references the quote and stores no amount, so every figure exists once. Confirmation never runs the engine again, so later price or lever changes cannot reach the order. A price change applies to new quotes only, and an open quote keeps its price until it expires: 15 minutes after it was created, a time to live that is country data.

**One ACID transaction per confirmation**, in READ COMMITTED with explicit row locks and a `lock_timeout`:

1. Insert the idempotency key. A concurrent request with the same key waits on it.
2. Lock the quote, then check its owner, that it has no order yet and that it has not expired.
3. When paying on credit, consume it with a conditional update that never exceeds the limit. If the total does not fit: 409 `CREDIT_LIMIT_EXCEEDED`, and the rollback leaves nothing behind.
4. Insert the order, which holds no amount, and, on credit, its ledger row.
5. Insert one outbox event for push and one for the ERP.
6. Store the 201 body on the idempotency key.
7. Commit, and only then send the 201.

Locks are always taken in the order key → quote → credit, so concurrent confirmations cannot deadlock.

**The 201 invariant.** The response is sent only after the commit, and order detail and history read the same primary tables. A returned order number therefore always points to a committed, readable row.

**Retries:**

| Situation | Response |
|-----------|----------|
| Same key, same body, already completed | The stored response, byte-identical, with `Idempotent-Replayed: true` |
| Same key, different body | 422 `IDEMPOTENCY_KEY_REUSED` |
| Same key while the first request is still running | Waits, then replays. 409 `IDEMPOTENCY_KEY_IN_PROGRESS` when `lock_timeout` passes first |
| A failed request (validation, expired quote, credit exceeded) | Not cached. The transaction rolls back and releases the key, so the client can fix the cause and retry |
| A new key on a confirmed quote | 409 `QUOTE_ALREADY_CONFIRMED`, with the existing order number |

Keys are kept for 24 hours. After that, a retry still receives `QUOTE_ALREADY_CONFIRMED` with the order number.

**External effects (transactional outbox).** Push and the ERP are reached only through the outbox events written with the order.

- With the default settings, the relay polls every second, claims due events with `FOR UPDATE SKIP LOCKED` and leases them for 30 seconds, so several instances can run at once.
- It calls push or the ERP outside any database transaction, passing the `event_id`.
- Success marks the event `SENT`. A failure is retried with exponential backoff and full jitter: the wait is drawn at random below a ceiling that starts at 2 seconds, doubles after each failed attempt and never exceeds 5 minutes. After 8 attempts, or at once on an error that retrying cannot fix, the event becomes `DEAD` (see Operations).
- **Never lost:** if the service stops between sending and marking, the lease expires and the event is sent again.
- **Never applied twice:** consumers deduplicate by `event_id`. The fake gateways in this repository do so. A real push would use it as the collapse id, and the ERP would upsert by order number.
- **External system down:** the order stays confirmed and the effect waits in the outbox. The `outbox_pending` gauge shows the backlog, and delivery resumes when the system recovers.

**The model and what it sacrifices:**

| Scope | Model | Sacrificed |
|-------|-------|------------|
| Order, credit, idempotency key, outbox events | Strong: one ACID transaction in one database | Availability while the database is down. Confirmations of the same customer serialize on its credit row |
| Push and ERP | Eventual: at-least-once delivery, deduplicated by consumers | They lag behind the confirmation by the poll interval plus any retries |

## Operations

### Requeue a DEAD outbox event

An event becomes `DEAD` after 8 failed attempts, or at once on an error that retrying cannot fix, such as an ERP 4xx. The relay stops sending it, the `outbox_dead` gauge counts it, and an ERROR log line says that it needs an operator. Housekeeping never deletes `DEAD` events. Fix the cause first, as `last_error` records it. Then, in the service's database (for example `docker compose exec db psql -U orderflow -d orderflow`), list the events and requeue one by its `tenant_id` and `id`:

```sql
SELECT tenant_id, id, order_id, destination, attempts, last_error, created_at
FROM outbox_events
WHERE status = 'DEAD'
ORDER BY created_at DESC;

UPDATE outbox_events
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_until = NULL
WHERE tenant_id = :tenant AND id = :id AND status = 'DEAD';
```

Replace `:tenant` and `:id` with the `tenant_id` and `id` that the first query returned, written as quoted literals, for example `'co-alpha'`.

Why this is safe:

- The relay claims `PENDING` events whose `next_attempt_at` has passed, so the next poll claims it and sends it.
- `attempts = 0` gives the event its full retry budget again.
- `last_error` is left untouched, so the last failure stays visible.
- The `status = 'DEAD'` condition makes the statement repeatable: on an event already requeued it changes nothing.
- Consumers deduplicate by `event_id`, so requeuing an event that had in fact been delivered has no second effect.

### Retention

A housekeeping job runs every hour, for every tenant. Each retention can be changed in `.env`, and `.env.example` names the property each key sets.

| Data | Kept for | Setting |
|------|----------|---------|
| Idempotency keys | 24 hours | `app.idempotency.retention` |
| Unconfirmed quotes | 7 days after they expire | `app.housekeeping.retention` |
| `SENT` outbox events | 7 days after they were written | `app.housekeeping.retention` |
| Orders, their quotes, and `DEAD` events | Never deleted | — |

## Golden tests

Every golden test is tagged `@Tag("golden")` and asserts exact amounts. A pricing case is a folder under `src/test/resources/golden/` with `request.json`, `snapshot.json` and `expected.json`. `GoldenPricingTest` checks every folder twice: against its expected breakdown, and against the identities that hold for any breakdown, such as total = Σ net + taxes.

| Assignment case | Fixture folder | Test |
|-----------------|----------------|------|
| 1. Discount in two brackets | `gt01-two-tier-brackets/` | `GoldenPricingTest` |
| 2. Combo without loose discounts | `gt02-combo-no-loose-discount/` | `GoldenPricingTest` |
| 3. Gift with its quantity | `gt03-gift/` | `GoldenPricingTest` |
| 4. Combo with taxes | `gt04-combo-with-taxes/` | `GoldenPricingTest` |
| 5. Credit order over the limit | `gt05-credit-exceeded/`, which adds `expected-quote.json` | `GoldenQuoteTest#gt05` for the quote. `CreditLimitIT` for the confirmation: 409, with no order and no ledger row |
| 6. Quote and confirm give the same total | None: it runs against PostgreSQL with the seeded data | `QuoteConfirmRegressionIT`: a price changes and a lever is deactivated between quote and confirmation. The confirmed total equals the quoted one, and a fresh quote differs |

More cases in the same harness: `gift-policy-exempt/`, `gift-policy-taxed/`, `rounding-half-cases/`, `multi-country-co-vs-ec/`, `co-no-levers/` and `ec-no-levers/`. `QuoteLeversIT` also replays the tier, combo and gift cases through `POST /api/v1/quotes` against the seeded data, so the levers are proven end to end, not only in the engine.

## ADR

[ADR-0001: Sealed quotes and a single-transaction confirmation](docs/adr/0001-order-pricing-and-confirmation.md), accepted. It covers the decisions and discarded alternatives, what was not implemented on purpose, the code review checklist, and the main risk with its mitigation.

## Not included

| Item | Why |
|------|-----|
| Authentication | Out of scope. Tenant and customer come from headers, and a gateway would inject them |
| Payment capture, cancellation and credit release | Not required |
| Cart persistence | Not required. The cart travels in the quote request |
| Quote revocation | Not required. It is the next step of the ADR's risk mitigation |
| Catalog and lever administration | Not required. The migrations seed the data |
| Tax categories and price history | Not required. Each SKU carries its tax rate, and each quote line keeps the price and rate in force when it was quoted |
| Real push and ERP clients | Fakes behind ports prove the delivery guarantees. They deduplicate by `event_id` and can be told to fail |
| OpenAPI file, deployment | Not required. The contract is `spec/api-contract.md` in the `orderflow-engine-docs` repository |
| SonarQube or SonarCloud | It needs a server. It would import the JaCoCo XML report this build already produces, so CI can add it without project changes |
| CI pipeline | Verification runs locally with `./mvnw verify`. A workflow would run the same command on every pull request |
| Mutation testing in CI | PIT runs in the optional `mutation` profile before delivery. A nightly job, or a run on the changed files, needs only CI configuration |
| Separate migration job and least-privilege database roles | Liquibase applies the schema when the service starts, with the owner's credential. In production a one-shot job would migrate with an owner role, the service would connect with a DML-only role, and the quote lock would move to `pg_advisory_xact_lock` |
| Separate schema repository | The changelogs live with their only owner, this service. Each one has a `logicalFilePath`, so they can move to their own repository without being applied again |

The adoption path of each of the last five rows (today's state, how it is adopted, and its trigger) is in `spec/plan.md` §3 of the `orderflow-engine-docs` repository.

Time spent: three working days (2026-10-13 to 2026-10-15), including the specification.

## Repositories

| Repository | Role |
|------------|------|
| `orderflow-engine-api` (this one) | The deliverable: code, tests, this README, the ADR |
| `orderflow-engine-docs` | Specification, contract, data model, architecture, diagrams |
| `orderflow-engine-web` | The optional order-summary screen |
| `orderflow-engine-infra` | The full stack with Docker Compose, and the workspace test command |
| `orderflow-engine-tools` | Generators and checks for the documentation and the repositories |
| `orderflow-engine-app` | Reserved for a future mobile app |
