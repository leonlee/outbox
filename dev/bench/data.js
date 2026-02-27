window.BENCHMARK_DATA = {
  "lastUpdate": 1772180360750,
  "repoUrl": "https://github.com/leonlee/outbox",
  "entries": {
    "Benchmark": [
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "aebe5bc6b018b36d293250d39cff0ea8a63b07d3",
          "message": "chore: bump version to 0.6.0-SNAPSHOT\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-13T18:37:55+09:00",
          "tree_id": "f1454dd9ccd3d0408a0a1852cfaea6c8b2e9053f",
          "url": "https://github.com/leonlee/outbox/commit/aebe5bc6b018b36d293250d39cff0ea8a63b07d3"
        },
        "date": 1770975812949,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6184.311229968344,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1459.4207544873882,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 354.7120586326668,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 10988.829175534773,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2628.6654484689134,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 666.4829620731401,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 69248.60181513049,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 67346.15326298073,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 54423.77597063287,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8420.134930508322,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8424.181993922559,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8432.603276655444,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "710420be65869ddf85f59d7ad9b62143a8b1169a",
          "message": "fix: address code review findings across all modules\n\n- Fix connection leak in JdbcTransactionManager.begin() when bind() or\n  setAutoCommit() throws (connection now closed in catch block)\n- Fix PostgresOutboxStore timestamp inconsistency: use separate nowMs\n  variable instead of reassigning the now parameter\n- Add null check to JdbcOutboxStores.get() for clear validation error\n- Simplify OutboxPoller.dispatchRows() oldest computation (rows are\n  already sorted oldest-first by SQL)\n- Validate empty namePrefix in MicrometerMetricsExporter\n- Centralize benchmark dependency versions (mysql-connector, postgresql,\n  HikariCP) in parent pom dependencyManagement\n- Narrow docs.yml path trigger (remove overly broad package-info glob)\n- Use MYSQL_PWD env var instead of -p flag in schema-diff workflow\n- Remove unused LinkedHashMap import in spring demo\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-16T14:41:26+09:00",
          "tree_id": "f8ccb17f6ec902875f0f45585a62ab0ebac710f1",
          "url": "https://github.com/leonlee/outbox/commit/710420be65869ddf85f59d7ad9b62143a8b1169a"
        },
        "date": 1771220814316,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6126.992195324729,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1481.404701460765,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 367.9639951279055,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11729.119046711892,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2640.6490257151086,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 672.7838287341335,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68053.2725353599,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 65398.761984075805,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 55694.699795244414,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8426.842016099887,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8423.884580650954,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8430.398574635243,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "19c6aa2e04959260590c35deb672e6d1a620490c",
          "message": "fix: defensive null checks, CI hardening, and benchmark consistency\n\n- Add Objects.requireNonNull on created_at in EVENT_ROW_MAPPER\n- Add ownerId null validation in all claimPending implementations\n- Log partial replay count in DeadEventManager.replayAll() on failure\n- Add explicit autoCommit in OutboxPollerBenchmark for DB portability\n- Add least-privilege permissions to CI workflow\n- Use environment variables in benchmark workflow to prevent injection\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-16T15:47:16+09:00",
          "tree_id": "4bdc0ad1a5e5e650464ef44a54dea060e3d6521c",
          "url": "https://github.com/leonlee/outbox/commit/19c6aa2e04959260590c35deb672e6d1a620490c"
        },
        "date": 1771224799292,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6290.114066383069,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1499.7379846269312,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 367.55616740959863,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11504.123259059286,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2621.5765810320677,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 674.7804519502524,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68975.77557644814,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 67303.64072440444,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 53714.25048097587,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8429.275638967452,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8427.634146762064,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8418.485980646781,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "a52c207ebc03df3a12bf1ef819c185eb3d99c566",
          "message": "test: improve coverage for defensive code, utilities, and edge cases\n\nAdd 36 new tests across 4 modules covering recently added null\nvalidations, untested utilities, and error-handling edge cases.\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-17T09:34:32+09:00",
          "tree_id": "83e9fd8dda5d680371265e9fb8b8d976a1818002",
          "url": "https://github.com/leonlee/outbox/commit/a52c207ebc03df3a12bf1ef819c185eb3d99c566"
        },
        "date": 1771288967629,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6474.788630582585,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1338.86139296435,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 361.7995063178824,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11548.055200058312,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2560.1374813517746,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 660.5405781426575,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 63985.63828653945,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 62898.14258459582,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 53624.10032670581,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8458.059544893378,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8460.677132435465,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8460.90682379349,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "fce60c4e1c05605962050b3f85ff27015751f2bc",
          "message": "bench: add MySQL benchmark results to benchmarks README\n\nRun all JMH benchmarks against local MySQL 9.6 (HikariCP, single-threaded,\n2s warmup x 2, 2s measurement x 3, 1 fork) and record results. Add\nPostgreSQL placeholder section for future collection.\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-17T11:11:17+09:00",
          "tree_id": "3e1d6599ebe84e4cd3d3584d8c47048975ccd541",
          "url": "https://github.com/leonlee/outbox/commit/fce60c4e1c05605962050b3f85ff27015751f2bc"
        },
        "date": 1771294599451,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 5953.971552537919,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1413.0431100482685,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 355.90902346223294,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 10879.460649015831,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2380.5816886365687,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 654.2178037756346,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 63947.47875821093,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 66738.98647565242,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 54673.00602753739,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8446.31881369248,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8448.804693041528,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8450.609470258138,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "5e32e65e0a03aafe24d185a805add1f774267034",
          "message": "bench: add PostgreSQL benchmark results\n\nRun all JMH benchmarks against Docker PostgreSQL 17 (HikariCP,\nsingle-threaded, 2s warmup x 2, 2s measurement x 3, 1 fork) and\nreplace the placeholder section with actual results.\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-17T11:27:28+09:00",
          "tree_id": "1d80625552b0788e127fbb5472f8a37e76d8c634",
          "url": "https://github.com/leonlee/outbox/commit/5e32e65e0a03aafe24d185a805add1f774267034"
        },
        "date": 1771295564977,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6414.134755754472,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1500.2638857208601,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 354.94442220078923,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11566.320233056898,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2505.294870525024,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 659.3504479816327,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68593.95703444761,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 63468.91184906869,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 56137.58647187397,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8414.055094003956,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8435.606646210852,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8435.452835578002,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "efab5aea90e40f20dfd1e919cc89b3a18b1d629f",
          "message": "chore: bump version to 0.7.0-SNAPSHOT\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-17T11:29:13+09:00",
          "tree_id": "70166ce543fcaee1d5a5e9b6071a0ed13f1ead19",
          "url": "https://github.com/leonlee/outbox/commit/efab5aea90e40f20dfd1e919cc89b3a18b1d629f"
        },
        "date": 1771295678113,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6431.314735411249,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1415.4569101224845,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 359.32212074717654,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 10687.570656397438,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2560.4916658654242,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 665.9312021076904,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 66925.99059862382,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 64318.86071355627,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 54800.88759972373,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8436.425351444052,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8445.682547773402,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8429.716560300483,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "6367fec525693b02822311b387f571cacdb5ade3",
          "message": "refactor: replace AfterCommitHook with WriterHook and revert batch dispatch\n\nWrite side: introduce WriterHook with richer lifecycle (beforeWrite/\nafterWrite/afterCommit/afterRollback), replace AfterCommitHook and\nDispatcherCommitHook, add OutboxStore.insertBatch SPI with batch SQL\nin AbstractJdbcOutboxStore, rewrite OutboxWriter with writeAll as\nprimary path and single afterCommit/afterRollback callback per batch.\n\nDispatch side: revert batch dispatch to single-event processing —\nQueuedEvent back to simple (envelope, source, attempts) record,\nremove dispatchBatch/deliverBatch/onEvents, DispatcherWriterHook\nenqueues events individually. Batch semantics belong on the write\npath, not the dispatch loop.\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-18T14:37:43+09:00",
          "tree_id": "5d618c3912f2fcf009e136450d9df309081952e3",
          "url": "https://github.com/leonlee/outbox/commit/6367fec525693b02822311b387f571cacdb5ade3"
        },
        "date": 1771393828112,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6138.364987539068,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1504.2488821843642,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 369.72870108943226,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11574.700882715128,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2622.809626033509,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 658.4785113930728,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68500.33407096192,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 67968.43949485938,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 52315.057642140106,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8448.152603815935,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8426.347934337786,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8449.462465768798,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "af4fa43944f2d76695f9b1692ecdd7380e46beef",
          "message": "chore: bump version to 0.7.2-SNAPSHOT",
          "timestamp": "2026-02-19T16:09:26+09:00",
          "tree_id": "ba6bd13e5fa2d25702d4c3dc38d13364c31a479c",
          "url": "https://github.com/leonlee/outbox/commit/af4fa43944f2d76695f9b1692ecdd7380e46beef"
        },
        "date": 1771485289526,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6126.514994667676,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1381.540586024701,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 369.48380588392956,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 10667.029821451084,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2631.0525111854918,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 656.3335638846482,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 67287.89886551455,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 66832.09871183908,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 55429.94785749535,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8430.671202020201,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8433.285089225588,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8436.637616722785,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "cbf8af8102966fabdf94164a7fb74d3a7b1f3003",
          "message": "chore: bump version to 0.7.3-SNAPSHOT",
          "timestamp": "2026-02-21T10:22:17+09:00",
          "tree_id": "0e65a2998721d8a6e92e77fd47df828ad58dd515",
          "url": "https://github.com/leonlee/outbox/commit/cbf8af8102966fabdf94164a7fb74d3a7b1f3003"
        },
        "date": 1771637270318,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6489.002748778049,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1476.7225915415277,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 363.5072117675316,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11411.49667652762,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2557.4657987716196,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 665.8171955907889,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68861.20000146939,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 63989.86742279135,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 51805.26990857491,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8439.000473625141,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8445.64157070707,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8462.014717732884,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "fed3a2a0afa424c7467789735ad2b4fc58c8fe7e",
          "message": "feat: configure Maven Central publishing\n\nChange groupId from 'outbox' to 'io.github.leonlee' for Maven Central\nnamespace compliance. Add required POM metadata (licenses, SCM,\ndevelopers), release profile with GPG signing and central-publishing\nplugin, and update CI workflow to deploy to Maven Central instead of\nGitHub Packages.\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-21T11:36:02+09:00",
          "tree_id": "63d5c4ebdd2c36e64ac9f34b5bd7428fd0b20a6a",
          "url": "https://github.com/leonlee/outbox/commit/fed3a2a0afa424c7467789735ad2b4fc58c8fe7e"
        },
        "date": 1771643083599,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6504.522034498835,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1504.4193351970437,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 364.33019549858477,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11358.88551743004,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2597.8704319703143,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 663.0105834648433,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68381.60441429437,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 63313.8533910707,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 55015.23818614003,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8450.661871492704,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8447.770286195286,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8446.358324354658,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "972c6942ec4d30a13d96a217593484a4c6b4cf04",
          "message": "chore: bump version to 0.8.1-SNAPSHOT\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-21T12:06:44+09:00",
          "tree_id": "77ffe9e077101011a0c72a3d0b2040886db4bfa8",
          "url": "https://github.com/leonlee/outbox/commit/972c6942ec4d30a13d96a217593484a4c6b4cf04"
        },
        "date": 1771643532314,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6495.254292416554,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1484.723943870437,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 321.17270850249633,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11352.716628908276,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2642.8483464182873,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 664.5457620262732,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68217.63160456203,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 67012.49454647825,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 50916.65880287697,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8453.665529180695,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8449.341689113355,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8458.031067340067,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "mail.lgq@gmail.com",
            "name": "Leon Lee",
            "username": "leonlee"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "6240ebe3980ad66487965136a731bcdd859bd8fc",
          "message": "Merge pull request #39 from leonlee/dependabot/maven/org.apache.maven.plugins-maven-shade-plugin-3.6.1\n\nbuild(deps-dev): bump org.apache.maven.plugins:maven-shade-plugin from 3.5.1 to 3.6.1",
          "timestamp": "2026-02-21T16:42:16+09:00",
          "tree_id": "6c8903e89a0489de6acf23058772c6704a9cac34",
          "url": "https://github.com/leonlee/outbox/commit/6240ebe3980ad66487965136a731bcdd859bd8fc"
        },
        "date": 1771660058986,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6775.437326881777,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1380.4662012692377,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 328.8305499218765,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11192.465080118733,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2469.4630449126125,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 596.4589435148933,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 64360.84922760518,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 61049.25397217827,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 46584.881367290516,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8457.547520763188,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8457.883851290686,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8461.14295959596,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "9d320d3eafba8969a844254418e95628f076e5ca",
          "message": "chore: bump version to 0.8.2-SNAPSHOT\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-21T16:56:50+09:00",
          "tree_id": "ac25dabb5949ca42b61a40bc5487d3dbc1234b1a",
          "url": "https://github.com/leonlee/outbox/commit/9d320d3eafba8969a844254418e95628f076e5ca"
        },
        "date": 1771660941106,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6395.272996120173,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1457.5411531639495,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 357.25399686361334,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 10696.957479330931,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2516.993787400607,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 654.3835918383363,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 67440.33120083345,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 65375.89537940887,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 52625.70770657209,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8438.338717171719,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8438.183856902357,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8440.866155443322,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "f258231a78b77cd3411a6c182dca501030920f05",
          "message": "style: format YAML files and Java classes for consistent indentation",
          "timestamp": "2026-02-24T18:05:23+09:00",
          "tree_id": "767bddd2616aea3e197e52b6ae31fa1a86407ddb",
          "url": "https://github.com/leonlee/outbox/commit/f258231a78b77cd3411a6c182dca501030920f05"
        },
        "date": 1771924263146,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 5987.479830386809,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1465.3372353754323,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 362.47654990199925,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11577.471349013927,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2633.774165262039,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 664.2928019784504,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 68358.35512437465,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 65729.45972187795,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 53501.544848687896,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8456.037591470258,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8450.960157687992,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8455.928868125702,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "448d12eb776ac2cb8d0fc3488dc0dc18b8d5ec07",
          "message": "chore: bump version to 0.8.3-SNAPSHOT\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-27T14:26:12+09:00",
          "tree_id": "54ac528ae3e296ffaaee6799c4c29e66c27e788b",
          "url": "https://github.com/leonlee/outbox/commit/448d12eb776ac2cb8d0fc3488dc0dc18b8d5ec07"
        },
        "date": 1772170305206,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6727.031681753407,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1361.5170588505077,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 324.0677575745413,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 11329.341909675233,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2446.128209979784,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 590.5291414913713,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 65292.17431644627,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 63523.129381159066,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 48129.43589556384,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8454.427510662177,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8462.222983164982,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8462.908667227834,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "committer": {
            "email": "felstormrider@gmail.com",
            "name": "Lee"
          },
          "distinct": true,
          "id": "da50341257873f41e2f3a1858961f0bba127ac49",
          "message": "chore: bump version to 0.8.4-SNAPSHOT\n\nCo-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-02-27T17:13:46+09:00",
          "tree_id": "419ba4217008f0522bf5e209cf80b3d5539fd68b",
          "url": "https://github.com/leonlee/outbox/commit/da50341257873f41e2f3a1858961f0bba127ac49"
        },
        "date": 1772180360287,
        "tool": "jmh",
        "benches": [
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 6151.923688495092,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 1494.9896747186096,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.claimAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 361.90250160931237,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"10\",\"database\":\"h2\"} )",
            "value": 10147.788555946376,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"50\",\"database\":\"h2\"} )",
            "value": 2616.9565305910105,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxPollerBenchmark.pollAndMarkDone ( {\"batchSize\":\"200\",\"database\":\"h2\"} )",
            "value": 640.5865777975795,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 64396.6817273107,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 64870.89007122165,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxWriteBenchmark.writeEvent ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 53943.90529949639,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"100\"} )",
            "value": 8436.455805274973,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"1000\"} )",
            "value": 8436.251832210999,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "outbox.benchmark.OutboxDispatchBenchmark.writeAndDispatch ( {\"database\":\"h2\",\"payloadSize\":\"10000\"} )",
            "value": 8437.475693602693,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}