package com.linkedin.metadata.dao;

import com.google.common.io.Resources;
import com.linkedin.common.AuditStamp;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.metadata.dao.utils.EmbeddedMariaInstance;
import com.linkedin.metadata.dao.utils.FooUrnPathExtractor;
import com.linkedin.metadata.dao.utils.SchemaValidatorUtil;
import com.linkedin.testing.AspectBar;
import com.linkedin.testing.AspectFoo;
import com.linkedin.testing.FooAsset;
import com.linkedin.testing.urn.FooUrn;
import io.ebean.Ebean;
import io.ebean.EbeanServer;
import io.ebean.SqlRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static com.linkedin.common.AuditStamps.*;
import static com.linkedin.testing.TestUtils.*;

/**
 * A/B latency + query-count benchmark for {@link EbeanLocalAccess#batchGetUnion}.
 *
 * <p>Same source compiles on both the pre-PR commit (N-queries-per-aspect path) and the PR branch
 * (single multi-aspect SELECT). Run it on each commit and compare the printed numbers:
 *
 * <pre>
 *   git checkout &lt;base-commit&gt;   # old code (N queries per aspect)
 *   ./gradlew :dao-impl:ebean-dao:test --tests '*EbeanLocalAccessBenchmarkTest*' -Dgma.benchmark=true
 *   git checkout &lt;pr-branch&gt;     # new code (single multi-aspect SELECT)
 *   ./gradlew :dao-impl:ebean-dao:test --tests '*EbeanLocalAccessBenchmarkTest*' -Dgma.benchmark=true
 * </pre>
 *
 * <p>Metrics:
 * <ul>
 *   <li><b>Latency</b> — p50/p90/max over {@code ITERATIONS} runs (empirical, relative — local
 *       EmbeddedMaria, not prod-representative in absolute terms).</li>
 *   <li><b>DB SELECT count</b> — read from MariaDB {@code Com_select} session status delta, i.e.
 *       how many SELECTs the DAO actually issued for one {@code batchGetUnion} call.</li>
 * </ul>
 *
 * <p>Disabled by default (skipped via {@link org.testng.SkipException}) so it does not run in the
 * normal suite/CI. Run explicitly with {@code -Dgma.benchmark=true} as shown above.
 */
public class EbeanLocalAccessBenchmarkTest {

  // Tune these to model different fanout scenarios.
  private static final int NUM_URNS = 50;      // URNs in the batch
  private static final int WARMUP = 20;        // warmup iterations (JIT + connection pool priming)
  private static final int ITERATIONS = 200;   // measured iterations

  private static EbeanServer _server;
  private static EbeanLocalAccess<FooUrn> _access;

  @BeforeClass
  public void init() {
    GlobalAssetRegistry.register(FooUrn.ENTITY_TYPE, FooAsset.class);
    _server = EmbeddedMariaInstance.getServer(EbeanLocalAccessBenchmarkTest.class.getSimpleName());
    _access = new EbeanLocalAccess<>(_server, EmbeddedMariaInstance.SERVER_CONFIG_MAP.get(_server.getName()),
        FooUrn.class, new FooUrnPathExtractor(), false);
  }

  @BeforeMethod
  public void setup() throws Exception {
    _server.execute(Ebean.createSqlUpdate(
        Resources.toString(Resources.getResource("ebean-local-access-create-all.sql"), StandardCharsets.UTF_8)));
    // Validator caches the schema at construction (before tables exist); refresh it now that
    // the tables are created so columnExists() sees the aspect columns (mirrors EbeanLocalAccessTest).
    final java.lang.reflect.Field validatorField = _access.getClass().getDeclaredField("validator");
    validatorField.setAccessible(true);
    validatorField.set(_access, new SchemaValidatorUtil(_server));
    // Seed NUM_URNS rows, each with 2 aspect columns populated (AspectFoo + AspectBar).
    for (int i = 0; i < NUM_URNS; i++) {
      final FooUrn urn = makeFooUrn(i);
      final AuditStamp auditStamp = makeAuditStamp("actor", System.currentTimeMillis());
      _access.add(urn, new AspectFoo().setValue("foo" + i), AspectFoo.class, auditStamp, null, false);
      _access.add(urn, new AspectBar().setValue("bar" + i), AspectBar.class, auditStamp, null, false);
    }
  }

  @Test
  public void benchmarkBatchGetUnion() {
    // Gate: skipped in normal suite/CI; run with -Dgma.benchmark=true.
    if (!Boolean.getBoolean("gma.benchmark")) {
      throw new org.testng.SkipException("benchmark disabled; enable with -Dgma.benchmark=true");
    }
    // Build keys: every URN x {AspectFoo, AspectBar} => NUM_URNS * 2 aspect keys.
    final List<AspectKey<FooUrn, ? extends RecordTemplate>> keys = new ArrayList<>();
    for (int i = 0; i < NUM_URNS; i++) {
      final FooUrn urn = makeFooUrn(i);
      keys.add(new AspectKey<>(AspectFoo.class, urn, 0L));
      keys.add(new AspectKey<>(AspectBar.class, urn, 0L));
    }
    final int keysCount = keys.size();

    // Warmup.
    int lastSize = 0;
    for (int w = 0; w < WARMUP; w++) {
      lastSize = _access.batchGetUnion(keys, keysCount, 0, false, false).size();
    }

    // Empirical DB SELECT count for a single call (Com_select session-status delta).
    // Pin one connection via a transaction so the SHOW STATUS reads and batchGetUnion's
    // internal SELECTs all run on the same MariaDB session (Ebean uses the thread-local txn).
    final long selectsForOneCall;
    try (io.ebean.Transaction txn = _server.beginTransaction()) {
      final long selectsBefore = comSelect();
      _access.batchGetUnion(keys, keysCount, 0, false, false);
      selectsForOneCall = comSelect() - selectsBefore;
      txn.commit();
    }

    // Timed runs.
    final long[] samplesNanos = new long[ITERATIONS];
    for (int it = 0; it < ITERATIONS; it++) {
      final long start = System.nanoTime();
      _access.batchGetUnion(keys, keysCount, 0, false, false);
      samplesNanos[it] = System.nanoTime() - start;
    }
    java.util.Arrays.sort(samplesNanos);

    System.out.println("================ batchGetUnion benchmark ================");
    System.out.printf("URNs=%d aspectsPerUrn=2 totalKeys=%d resultRows=%d%n", NUM_URNS, keysCount, lastSize);
    System.out.printf("DB SELECTs issued for ONE call = %d%n", selectsForOneCall);
    System.out.printf("latency p50=%.3f ms  p90=%.3f ms  max=%.3f ms  (over %d iters)%n",
        toMs(samplesNanos[(int) (ITERATIONS * 0.50)]),
        toMs(samplesNanos[(int) (ITERATIONS * 0.90)]),
        toMs(samplesNanos[ITERATIONS - 1]),
        ITERATIONS);
    System.out.println("=========================================================");
  }

  private static double toMs(long nanos) {
    return nanos / 1_000_000.0;
  }

  /**
   * Read MariaDB's per-session {@code Com_select} counter to measure how many SELECTs were run.
   */
  private static long comSelect() {
    final List<SqlRow> rows = _server.createSqlQuery("SHOW SESSION STATUS LIKE 'Com_select'").findList();
    // The SHOW itself is a query but not a SELECT, so it does not inflate Com_select.
    return rows.isEmpty() ? -1 : Long.parseLong(rows.get(0).getString("Value"));
  }
}
