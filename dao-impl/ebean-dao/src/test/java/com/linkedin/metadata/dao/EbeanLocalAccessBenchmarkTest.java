package com.linkedin.metadata.dao;

import com.google.common.io.Resources;
import com.linkedin.common.urn.Urn;
import com.linkedin.metadata.dao.utils.EmbeddedMariaInstance;
import com.linkedin.metadata.dao.utils.SQLStatementUtils;
import io.ebean.Ebean;
import io.ebean.EbeanServer;
import io.ebean.SqlRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.linkedin.metadata.dao.utils.SQLStatementUtils.SOFT_DELETED_CHECK;
import static com.linkedin.metadata.dao.utils.SQLStatementUtils.DELETED_TS_IS_NULL_CHECK;
import static com.linkedin.testing.TestUtils.*;

/**
 * A/B latency + query-count benchmark for the multi-aspect read optimization in
 * <a href="https://github.com/linkedin/datahub-gma/pull/622">PR #622</a>
 * (META-24100): collapse N per-aspect SQL reads in {@code EbeanLocalAccess.batchGetUnion}
 * into a single multi-aspect {@code SELECT}.
 *
 * <p>To exercise a production-representative aspect fanout (73 aspects on one entity), this
 * benchmark provisions a synthetic entity table with {@link #NUM_ASPECTS} aspect columns (the
 * shared test models expose only 2) and compares the two <b>real</b> SQL shapes in a single run:
 *
 * <ul>
 *   <li><b>Old path</b> — one SELECT per aspect column, each with the
 *       {@code JSON_EXTRACT(col, '$.gma_deleted') IS NULL} soft-delete filter, exactly as
 *       {@code SQLStatementUtils.createAspectReadSql} emits (replicated here from
 *       {@code SQL_READ_ASPECT_TEMPLATE} because that builder requires a registered aspect
 *       class per column). =&gt; {@code NUM_ASPECTS} round-trips.</li>
 *   <li><b>New path</b> — the actual {@link SQLStatementUtils#createMultiAspectReadSql} builder:
 *       one SELECT listing all aspect columns, no {@code JSON_EXTRACT}. =&gt; 1 round-trip
 *       (per {@code MAX_URNS_PER_QUERY} chunk).</li>
 * </ul>
 *
 * <p>Metrics per path:
 * <ul>
 *   <li><b>DB SELECT count</b> — MariaDB {@code Com_select} session-status delta (connection
 *       pinned in a transaction) for one logical read.</li>
 *   <li><b>Latency</b> — p50 / p90 / max over {@link #ITERATIONS} iterations.</li>
 * </ul>
 *
 * <p>Absolute latency is not prod-representative (in-process DB, no network); the meaningful
 * takeaways are the query-count collapse (N -&gt; 1) and the relative latency reduction. Skipped in
 * the normal suite/CI; run explicitly with {@code -Dgma.benchmark=true}:
 *
 * <pre>
 *   ./gradlew :dao-impl:ebean-dao:test --tests '*EbeanLocalAccessBenchmarkTest*' -Dgma.benchmark=true
 * </pre>
 */
public class EbeanLocalAccessBenchmarkTest {

  // Tune these to model different fanout scenarios.
  private static final int NUM_ASPECTS = 73;   // aspect columns per entity (matches PR #622's Dataset example)
  private static final int NUM_URNS = 50;      // URNs in the batch
  private static final int WARMUP = 20;        // warmup iterations (JIT + connection pool priming)
  private static final int ITERATIONS = 200;   // measured iterations

  private static final String TABLE = "metadata_entity_foo";
  private static final String COLUMN_PREFIX = "a_bench";

  private static EbeanServer _server;

  @BeforeClass
  public void init() {
    _server = EmbeddedMariaInstance.getServer(EbeanLocalAccessBenchmarkTest.class.getSimpleName());
  }

  @BeforeMethod
  public void setup() throws Exception {
    _server.execute(Ebean.createSqlUpdate(
        Resources.toString(Resources.getResource("ebean-local-access-create-all.sql"), StandardCharsets.UTF_8)));
    // Add NUM_ASPECTS synthetic JSON aspect columns to the foo entity table.
    for (int a = 0; a < NUM_ASPECTS; a++) {
      _server.execute(Ebean.createSqlUpdate("ALTER TABLE " + TABLE + " ADD COLUMN " + COLUMN_PREFIX + a + " JSON"));
    }
    // Seed NUM_URNS rows with all aspect columns populated (valid JSON, no gma_deleted marker).
    final String colList = String.join(", ", aspectColumns());
    for (int i = 0; i < NUM_URNS; i++) {
      final String values = aspectColumns().stream()
          .map(c -> "JSON_OBJECT('value', '" + c + "_v')")
          .collect(Collectors.joining(", "));
      _server.execute(Ebean.createSqlUpdate(
          "INSERT INTO " + TABLE + " (urn, lastmodifiedon, lastmodifiedby, " + colList + ") VALUES ('"
              + makeFooUrn(i) + "', NOW(), 'actor', " + values + ")"));
    }
  }

  @Test
  public void benchmarkMultiAspectRead() {
    // Gate: skipped in normal suite/CI; run with -Dgma.benchmark=true.
    if (!Boolean.getBoolean("gma.benchmark")) {
      throw new org.testng.SkipException("benchmark disabled; enable with -Dgma.benchmark=true");
    }

    final Set<String> aspectColumns = new LinkedHashSet<>(aspectColumns());
    final Set<Urn> urns = new LinkedHashSet<>();
    for (int i = 0; i < NUM_URNS; i++) {
      urns.add(makeFooUrn(i));
    }

    // Pre-build SQL for each path.
    final List<String> oldSqls = buildOldPerAspectSqls(aspectColumns, urns);
    final String newSql = SQLStatementUtils.createMultiAspectReadSql(aspectColumns, urns, false, false);

    // Warmup both paths.
    for (int w = 0; w < WARMUP; w++) {
      runOldPath(oldSqls);
      _server.createSqlQuery(newSql).findList();
    }

    // Empirical DB SELECT counts for one logical read (Com_select session-status delta, connection
    // pinned via a transaction so all SELECTs run on the same MariaDB session).
    final long oldSelects;
    try (io.ebean.Transaction txn = _server.beginTransaction()) {
      final long before = comSelect();
      runOldPath(oldSqls);
      oldSelects = comSelect() - before;
      txn.commit();
    }
    final long newSelects;
    try (io.ebean.Transaction txn = _server.beginTransaction()) {
      final long before = comSelect();
      _server.createSqlQuery(newSql).findList();
      newSelects = comSelect() - before;
      txn.commit();
    }

    final long[] oldNanos = time(() -> runOldPath(oldSqls));
    final long[] newNanos = time(() -> _server.createSqlQuery(newSql).findList());

    System.out.println("================ multi-aspect read benchmark ================");
    System.out.printf("URNs=%d aspectsPerUrn=%d totalKeys=%d  (over %d iters)%n",
        NUM_URNS, NUM_ASPECTS, NUM_URNS * NUM_ASPECTS, ITERATIONS);
    System.out.printf("%-6s | %-16s | %-9s | %-9s | %-9s%n", "path", "DB SELECTs/read", "p50 ms", "p90 ms", "max ms");
    System.out.printf("%-6s | %-16d | %-9.3f | %-9.3f | %-9.3f%n",
        "old", oldSelects, toMs(pct(oldNanos, 0.50)), toMs(pct(oldNanos, 0.90)), toMs(oldNanos[ITERATIONS - 1]));
    System.out.printf("%-6s | %-16d | %-9.3f | %-9.3f | %-9.3f%n",
        "new", newSelects, toMs(pct(newNanos, 0.50)), toMs(pct(newNanos, 0.90)), toMs(newNanos[ITERATIONS - 1]));
    System.out.println("============================================================");
  }

  /**
   * Replicates {@code SQLStatementUtils.createAspectReadSql} output for one aspect column.
   */
  private static List<String> buildOldPerAspectSqls(Set<String> aspectColumns, Set<Urn> urns) {
    final String urnList = urns.stream()
        .map(u -> "'" + SQLStatementUtils.escapeReservedCharInUrn(u.toString()) + "'")
        .collect(Collectors.joining(", "));
    final List<String> sqls = new ArrayList<>();
    for (String col : aspectColumns) {
      final String softDeleteCheck = String.format(SOFT_DELETED_CHECK, col);
      sqls.add("SELECT urn, " + col + ", lastmodifiedon, lastmodifiedby FROM " + TABLE
          + " WHERE " + softDeleteCheck + " AND urn IN (" + urnList + ") AND " + DELETED_TS_IS_NULL_CHECK);
    }
    return sqls;
  }

  private static void runOldPath(List<String> oldSqls) {
    for (String sql : oldSqls) {
      _server.createSqlQuery(sql).findList();
    }
  }

  private static long[] time(Runnable r) {
    final long[] samples = new long[ITERATIONS];
    for (int i = 0; i < ITERATIONS; i++) {
      final long start = System.nanoTime();
      r.run();
      samples[i] = System.nanoTime() - start;
    }
    Arrays.sort(samples);
    return samples;
  }

  private static List<String> aspectColumns() {
    final List<String> cols = new ArrayList<>();
    for (int a = 0; a < NUM_ASPECTS; a++) {
      cols.add(COLUMN_PREFIX + a);
    }
    return cols;
  }

  private static long pct(long[] sorted, double p) {
    return sorted[(int) (sorted.length * p)];
  }

  private static double toMs(long nanos) {
    return nanos / 1_000_000.0;
  }

  /**
   * Read MariaDB's per-session {@code Com_select} counter to measure how many SELECTs were run.
   */
  private static long comSelect() {
    final List<SqlRow> rows = _server.createSqlQuery("SHOW SESSION STATUS LIKE 'Com_select'").findList();
    return rows.isEmpty() ? -1 : Long.parseLong(rows.get(0).getString("Value"));
  }
}
