# Order & Inventory Service

A small Spring Boot REST service for placing orders against limited stock. It exists to get the hard parts of that problem right: never overselling under concurrency, making retries safe, and keeping order state changes valid.

**Stack:** Java 21 · Spring Boot 4 · Spring Data JPA (Hibernate) · PostgreSQL 16 · Flyway · JUnit 5 · Mockito · Maven · Docker Compose

## What it does

| Endpoint | Behaviour |
|---|---|
| `POST /products` | Create a product with a SKU, price and stock |
| `POST /orders` (header `Idempotency-Key`) | Reserve stock for every line and create the order. 201 when created, 200 when the same key is retried |
| `POST /orders/{id}/pay` | `PLACED` to `PAID` |
| `POST /orders/{id}/cancel` | `PLACED` or `PAID` to `CANCELLED`, returning the reserved stock |
| `GET /orders/{id}` | Order with its items |
| `GET /orders?status=&from=&to=&page=&size=` | Newest-first search, served by a composite index |

Errors are consistent JSON: 400 validation, 404 not found, 409 out of stock or invalid transition, 422 idempotency key reused with a different body.

## Design decisions

**Stock is reserved by one SQL statement, not read-then-write.**
`UPDATE products SET stock = stock - :qty WHERE id = :id AND stock >= :qty` checks and decrements atomically. Two buyers can never both take the last unit, and no lock is held in Java. A `CHECK (stock >= 0)` constraint backs it up in the database.

**All-or-nothing orders.** Every line is reserved inside one transaction. If the third line is out of stock, the first two reservations roll back with it.

**Deadlock avoidance.** Lines are always reserved in product-id order, so two orders that list the same products in opposite order cannot lock each other.

**Idempotency.** `Idempotency-Key` is `UNIQUE` on `orders`. A retry returns the original order and takes no stock. A key reused with a different body is rejected (a hash of the canonical request is stored). When two first attempts race, the unique constraint picks the winner and the loser rolls back, including its reservation, and returns the winner's order.

**Order state machine.** `OrderStatus` owns the allowed transitions. Each transition is a compare-and-set (`UPDATE ... WHERE status = :from`), so a concurrent pay and cancel cannot both succeed. One of them gets a 409.

**Layering.** Controller (HTTP and validation only) → Service (rules and transactions) → Repository (SQL). Domain errors live in one class and one `@RestControllerAdvice` maps them to HTTP statuses.

## Tests: 31

```bash
docker compose up -d          # PostgreSQL on :5434
./mvnw test                   # 24 unit tests, no database
./mvnw verify                 # + 7 integration tests against real PostgreSQL
```

- **Unit (24):** transition table; stock reserved in id order and totals priced; out-of-stock fails the whole order; idempotent retry and key reuse; losing the unique-key race; cancel restocks and pay does not; pay-vs-cancel loser is reported; HTTP status mapping and validation.
- **Integration (7, real PostgreSQL):**
  - 80 concurrent buyers for 25 units: exactly 25 succeed, 55 get "sold out", stock ends at 0 and 25 units are recorded as sold.
  - 30 simultaneous requests with one idempotency key: exactly 1 order and stock reserved once.
  - A failed second line rolls back the first.
  - 40 concurrent orders listing two products in opposite order: no deadlock.
  - Cancel restores stock, and a second cancel does not restock again.
  - Pay racing cancel (10 rounds): one winner and consistent stock.
  - A replayed order still carries its items (regression, see below).

The integration tests use the real database because the guarantees under test (atomic updates, unique constraints, rollback) are the database's; an in-memory substitute would prove nothing.

### A bug the tests missed

A `curl` smoke test of the running service showed an idempotent retry returning **500 instead of 200**. The replay path loaded the existing order without its items (lazy), and building the response failed with `LazyInitializationException` because open-in-view is off. The service tests never read the items on a replay and the controller test mocks the service, so neither caught it. The repository query now fetches the items, and `aReplayedOrderStillHasItsItemsForTheResponse` guards it.

## Benchmark: the search index

`./mvnw test -Dtest=SearchIndexBenchmarkBench` seeds 500,000 orders (8% `PLACED`, 67% `PAID`, 25% `CANCELLED`, spread over a year) and times "newest `PLACED` orders from the last 7 days, page of 20" through the service, 40 runs after warm-up:

| | p50 | p95 | Plan |
|---|---|---|---|
| no index | 23.6 ms | 35.5 ms | Parallel Seq Scan + Sort |
| `idx_orders_status_created (status, created_at DESC)` | **3.8 ms** | 6.2 ms | Index Scan, no sort |

**6.3× at p50.** These timings include Hibernate and connection overhead on a laptop, so the database-only gain is larger. The query returns a `Slice`, so it skips the second `COUNT(*)` a `Page` would run. Full output is in `docs/benchmark.json`.

## Not built (on purpose)

Authentication, payments and refunds, a message queue, and per-warehouse stock. Cancelling a paid order restocks it but does not refund. There is no soft delete.
