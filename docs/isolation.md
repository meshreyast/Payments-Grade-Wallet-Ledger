# PostgreSQL isolation lab

Open two shells with `docker compose exec postgres psql -U wallet -d wallet`.

## Read Committed

Session A:
```sql
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance_paise FROM accounts WHERE id = <wallet_id>;
-- pause
SELECT balance_paise FROM accounts WHERE id = <wallet_id>;
COMMIT;
```
Session B between the reads:
```sql
UPDATE accounts SET balance_paise=balance_paise+100 WHERE id=<wallet_id>;
COMMIT;
```
A can see a different value on its second statement because each statement gets a new snapshot.

## Repeatable Read

Repeat with `BEGIN ISOLATION LEVEL REPEATABLE READ`. A should keep seeing its original snapshot.

## Serializable

Use two accounts representing two on-call operators. Each transaction reads the number of active operators and disables a different one only if more than one is active. Under Serializable, one transaction may fail with SQLSTATE `40001`; retry the entire transaction.

## Join behavior

Repeat the Repeatable Read case with a join between `accounts` and `users`. The transaction snapshot applies to the joined query too.

## Transfer decision

The starter uses transaction boundaries and `SELECT ... FOR UPDATE` locks in ascending account ID order, then checks the balance while locked. Read Committed with those locks is sufficient for the wallet non-negative invariant if all balance-changing code follows the same protocol. Serializable can detect additional anomalies but adds aborts and retry work.
