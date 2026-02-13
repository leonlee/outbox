window.BENCHMARK_DATA = {
  "lastUpdate": 1770975813928,
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
      }
    ]
  }
}