# `batchGetUnion` multi-aspect read benchmark

Benchmark harness and results for the optimization in [PR #622](https://github.com/linkedin/datahub-gma/pull/622):
collapse _N_ per-aspect SQL reads in `EbeanLocalAccess.batchGetUnion` into a single multi-aspect `SELECT` (with 100-URN
IN-clause chunking), tracked in [META-24100](https://linkedin.atlassian.net/browse/META-24100).

## What it measures

To exercise a production-representative aspect fanout (73 aspects on one entity, as in PR #622's Dataset example), the
benchmark provisions a synthetic entity table with `NUM_ASPECTS` aspect columns (the shared test models expose only 2)
and compares the two **real** SQL shapes in a single run:

- **Old path** — one SELECT per aspect column, each with the `JSON_EXTRACT(col, '$.gma_deleted') IS NULL` soft-delete
  filter, exactly as the pre-PR `SQLStatementUtils.createAspectReadSql` emits (replicated from
  `SQL_READ_ASPECT_TEMPLATE`, since that builder requires a registered aspect class per column). ⇒ `NUM_ASPECTS`
  round-trips.
- **New path** — the actual `SQLStatementUtils.createMultiAspectReadSql` builder added by PR #622: one SELECT listing
  all aspect columns, no `JSON_EXTRACT`. ⇒ 1 round-trip (per `MAX_URNS_PER_QUERY` chunk).

For each path it reports:

- **DB SELECT count** — read from MariaDB's per-session `Com_select` status counter (the connection is pinned inside a
  transaction so the count reflects exactly the SELECTs that ran for one logical read). The "new = 1" figure is
  therefore a measured value, not an assumption.
- **Latency** — p50 / p90 / max over 200 iterations.

## Local results (EmbeddedMariaDB, 50 URNs × 73 aspects = 3650 keys)

| Path                                 | DB SELECTs / read | p50      | p90      | max      |
| ------------------------------------ | ----------------- | -------- | -------- | -------- |
| **Old** (1 SELECT per aspect column) | **73**            | 24.26 ms | 27.17 ms | 34.99 ms |
| **New** (single multi-aspect SELECT) | **1**             | 1.60 ms  | 1.76 ms  | 2.03 ms  |

**73 → 1 SELECT, ~15× lower p50 (24.26 ms → 1.60 ms).** The SELECT count is `#aspect-columns` for the old path vs
`ceil(#URNs / 100)` for the new path — so the win scales with aspect fanout. Absolute latency is not prod-representative
(in-process DB, no network); production-scale latency is validated on EI (see below).

## How to run locally

Prerequisites (Apple Silicon):

1. Use **Java 11** (Gradle 6.9.4 is incompatible with newer JDKs):
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 11)
   ```
2. Install MariaDB and enable the M-series path in the embedded harness:
   ```bash
   brew install mariadb
   ```
   Then uncomment the three `configurationBuilder.set*` lines in `EmbeddedMariaInstance.java` (marked "M1 / M2 chip").
   **Local-only — do not commit.**

Run the benchmark (skipped by default; enabled with `-Dgma.benchmark=true`). Both the old and new SQL shapes are
measured in a single run — no implementation swapping needed:

```bash
./gradlew :dao-impl:ebean-dao:test --tests '*EbeanLocalAccessBenchmarkTest*' -Dgma.benchmark=true
```

Results are printed to the test's captured stdout, e.g.:
`build/test-results/test/TEST-com.linkedin.metadata.dao.EbeanLocalAccessBenchmarkTest.xml` (look for the `<system-out>`
block), or in the HTML report under `build/reports/tests/test/`.

Tune `NUM_ASPECTS`, `NUM_URNS`, `WARMUP`, and `ITERATIONS` at the top of the test to model other batch sizes.

## EI (QEI) testing — production-scale latency

Local numbers use an in-process DB with no network, so they are only relative. To get production-representative latency
and the full aspect-fanout story, verify on QEI as in PR #622:

1. **Build & deploy** the datahub-gma JAR to `qei-ltx1` via the `test-gma-changes` workflow.
2. **Single-query path (all aspects)** — Dataset get hydrating all ~73 aspects:
   ```bash
   grpcurli --dv-auth SELF -f ei-ltx1 localhost:25403 \
     proto.com.linkedin.mg.assets.service.DatasetAssetService.get \
     -d '{"key":{"urn":{"platform":{"platformName":"gridTable"},"datasetName":"test_create_009","origin":"FabricType_EI"}}}'
   ```
   Confirm in the logs: `[batchGetUnion] Executing single query: ... aspectColumns=72` and
   `[batchGetHelper] NEW_SCHEMA_ONLY: skipping position=50` (pagination guard). One SQL for 73 aspects vs 73 in the old
   path.
3. **Specific aspects** — pass `aspectTypes` (e.g. `Status`, `RetentionPolicy`) and confirm only the requested columns
   are selected.
4. **`getWithContext` / `filter` / `batchGet`** — exercise multi-URN hydration and confirm a single `batchGetUnion` per
   chunk.
5. **Soft-delete** — update an aspect, `delete` it, then `get`: confirm the SQL has **no `JSON_EXTRACT`** and the
   soft-deleted aspect is filtered in Java (`[readMultiAspectSqlRows] Soft-deleted aspect: ...`).
6. **Latency capture** — run each read many times and record p50/p90/p99 with old vs new JARs to quantify the SLO
   recovery (Code Yellow goal). Attach the before/after numbers to META-24100.

## Rollout ordering (de-risked)

`OLD_SCHEMA_ONLY` → `DUAL_SCHEMA` (writes both, compares old vs new on every read; watch for zero comparison mismatches)
→ `NEW_SCHEMA_ONLY` (config-gated flip once parity is clean in prod), followed by removal of the dual-comparison
overhead.
