# Digital Wallet — Java / Spring Boot / PostgreSQL

Learning project for double-entry accounting, safe concurrent transfers, idempotency and nightly reconciliation.

## Run

Requires Docker Compose. Run:

```bash
docker compose up --build
```

API: `http://localhost:8080`. PostgreSQL is available on port 5432. Credentials are for local development only.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/users` | Create user |
| POST | `/users/{id}/wallets` | Open wallet |
| GET | `/wallets/{id}/balance` | Current balance |
| GET | `/wallets/{id}/statement?limit=50&offset=0` | Newest-first statement |
| POST | `/transfers/topup` | Cash-in to wallet |
| POST | `/transfers` | Wallet to wallet |
| POST | `/transfers/withdraw` | Wallet to cash-out |
| GET | `/transfers/{id}` | Transfer, ledger rows and status history |

All transfer POSTs require `Idempotency-Key: <UUID>` and `X-User-Id: <user id>`. The latter is a development-only identity shortcut, not authentication. Replace it with an authenticated principal before any deployment.

Amounts are paise: ₹500 = `50000`.

Create a user:
```bash
curl -X POST http://localhost:8080/users -H 'Content-Type: application/json' -d '{"name":"Asha","email":"asha@example.test","phone":"+919999000001"}'
```

## Design notes

- `docs/schema.md` contains the noun list, cardinalities and ER diagram.
- `docs/isolation.md` contains the two-session isolation lab.
- Money uses `BIGINT` paise only.
- Each completed transfer writes debit and credit ledger entries in one database transaction.
- Wallets and system accounts share one account table.
- Ledger entries are append-only at the database level.
- The transfer service uses explicit JDBC SQL and ordered row locks.
- Reconciliation runs in a Repeatable Read transaction and stores mismatches without repairing them.
- Statements use `LIMIT/OFFSET`; high offsets become slower because PostgreSQL must walk and discard earlier rows. Cursor pagination on `(created_at,id)` is a good next improvement.

## Important scope / known gaps

This is a starter implementation, not a certified payment system. It includes schema migrations, basic user/wallet endpoints, top-up/peer transfer/withdrawal posting, idempotency response storage, one configurable large-transfer hold rule, and reconciliation SQL. Before treating all requested acceptance criteria as complete, implement and test:
- concurrent stampede and parallel idempotency acceptance tests;
- reversal endpoint, admin risk queue/release/reverse decisions, and manual reconciliation report endpoint;
- velocity fraud rules and LLM explanation + statement Q&A behind a provider interface with mock;
- seed generator for 1,000 users, 2,000 wallets, 100,000 transfers and EXPLAIN ANALYZE against one million entries;
- authenticated authorization, robust retry handling, production secrets, rate limiting, and byte-for-byte replay tests;
- full integration tests for the exact three-corruption reconciliation scenario.

Never use this sample to move real money without a security review, operational controls and full acceptance testing.
