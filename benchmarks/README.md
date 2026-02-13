# Benchmarks

JMH benchmarks for the outbox framework, measuring write throughput, hot-path dispatch latency, and poller throughput.

## Benchmarks

| Benchmark | Metric | Parameters |
|-----------|--------|------------|
| `OutboxWriteBenchmark` | Throughput (ops/sec) | `payloadSize`: 100, 1000, 10000 bytes |
| `OutboxDispatchBenchmark` | Average latency (µs) | `payloadSize`: 100, 1000, 10000 bytes |
| `OutboxPollerBenchmark` | Throughput (ops/sec) | `batchSize`: 10, 50, 200 events |

All benchmarks support `database` parameter: `h2` (default), `mysql`, or `postgresql`.

### Implementation Notes

- **Batch `markDone`**: `OutboxPollerBenchmark` uses `markDoneBatch` (single `UPDATE ... WHERE event_id IN (...)`) instead of per-event `markDone` calls, reducing round-trips from N to 1.
- **Connection pooling**: MySQL and PostgreSQL benchmarks use HikariCP (max 10, min idle 2). H2 stays unwrapped (in-memory, negligible connection overhead).

## Sample Results (H2, JDK 21)

> Single-threaded, 2s warmup x 2, 2s measurement x 3, 1 fork. MacBook environment — not a stable benchmarking host; numbers are indicative, not authoritative.

### Write Throughput

| Payload Size | Throughput (ops/s) | Error (99.9%) |
|-------------:|-------------------:|--------------:|
| 100 B        | 152,146            | ± 43,103      |
| 1,000 B      | 139,973            | ± 117,934     |
| 10,000 B     | 103,537            | ± 179,220     |

### Dispatch Latency

| Payload Size | Avg Latency (µs/op) | Error (99.9%) |
|-------------:|--------------------:|--------------:|
| 100 B        | 9,124               | ± 2,789       |
| 1,000 B      | 9,173               | ± 1,293       |
| 10,000 B     | 9,152               | ± 149         |

### Poller Throughput (batch markDone)

| Batch Size | Throughput (ops/s) | Error (99.9%) |
|-----------:|-------------------:|--------------:|
| 10         | 428                | ± 1,394       |
| 50         | 189                | ± 550         |
| 200        | 83                 | ± 72          |

## Running

### Build

```bash
mvn -pl benchmarks -am clean package -DskipTests
```

### H2 (in-memory, default)

```bash
# All benchmarks
java -jar benchmarks/target/benchmarks.jar

# Single benchmark
java -jar benchmarks/target/benchmarks.jar OutboxWriteBenchmark

# Quick smoke test
java -jar benchmarks/target/benchmarks.jar -p database=h2 -wi 1 -i 1 -f 1 -r 1 OutboxWriteBenchmark
```

### MySQL

Requires a running MySQL instance with an `outbox_bench` database:

```bash
mysql -u root -e "CREATE DATABASE IF NOT EXISTS outbox_bench"
```

```bash
# Using defaults (localhost:3306, root, empty password)
java -jar benchmarks/target/benchmarks.jar -p database=mysql

# Custom connection
java -Dbench.mysql.url=jdbc:mysql://host:3306/outbox_bench \
     -Dbench.mysql.user=myuser \
     -Dbench.mysql.password=mypass \
     -jar benchmarks/target/benchmarks.jar -p database=mysql
```

### PostgreSQL

Requires a running PostgreSQL instance with an `outbox_bench` database:

```bash
createdb -U postgres outbox_bench
```

```bash
# Using defaults (localhost:5432, postgres/postgres)
java -jar benchmarks/target/benchmarks.jar -p database=postgresql

# Custom connection
java -Dbench.pg.url=jdbc:postgresql://host:5432/outbox_bench \
     -Dbench.pg.user=myuser \
     -Dbench.pg.password=mypass \
     -jar benchmarks/target/benchmarks.jar -p database=postgresql
```

The schema (`outbox_event` table + index) is created automatically via `CREATE TABLE IF NOT EXISTS`.

### JMH Options

```bash
# JSON output for CI/tooling
java -jar benchmarks/target/benchmarks.jar -rf json -rff result.json

# Filter by regex
java -jar benchmarks/target/benchmarks.jar "OutboxWrite|OutboxPoller"

# Override iterations (warmup/measurement/forks)
java -jar benchmarks/target/benchmarks.jar -wi 2 -i 3 -f 1
```

## System Properties

### MySQL

| Property | Default | Description |
|----------|---------|-------------|
| `bench.mysql.url` | `jdbc:mysql://localhost:3306/outbox_bench` | JDBC URL |
| `bench.mysql.user` | `root` | Database user |
| `bench.mysql.password` | _(empty)_ | Database password |

### PostgreSQL

| Property | Default | Description |
|----------|---------|-------------|
| `bench.pg.url` | `jdbc:postgresql://localhost:5432/outbox_bench?stringtype=unspecified` | JDBC URL |
| `bench.pg.user` | `postgres` | Database user |
| `bench.pg.password` | `postgres` | Database password |

## CI

The [Benchmarks workflow](../.github/workflows/benchmark.yml) runs on:

- **Push to `main`** when `benchmarks/**` changes — H2 only, results tracked on `gh-pages` with 20% regression alerting
- **Manual dispatch** — choose H2, MySQL, or PostgreSQL, with optional JMH filter regex
