window.BENCHMARK_DATA = {
  "lastUpdate": 1771220815156,
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
      }
    ]
  }
}