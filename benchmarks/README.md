# Benchmarks

JMH benchmarks for the outbox framework, measuring write throughput, hot-path dispatch latency, and poller throughput.

## Benchmarks

| Benchmark | Metric | Parameters |
|-----------|--------|------------|
| `OutboxWriteBenchmark` | Throughput (ops/sec) | `payloadSize`: 100, 1000, 10000 bytes |
| `OutboxDispatchBenchmark` | Average latency (µs) | `payloadSize`: 100, 1000, 10000 bytes |
| `OutboxPollerBenchmark.pollAndMarkDone` | Throughput (ops/sec) | `batchSize`: 10, 50, 200 events |
| `OutboxPollerBenchmark.claimAndMarkDone` | Throughput (ops/sec) | `batchSize`: 10, 50, 200 events |

All benchmarks support `database` parameter: `h2` (default), `mysql`, or `postgresql`.

### Implementation Notes

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

### Poller Throughput — `pollAndMarkDone`

| Batch Size | Throughput (ops/s) | Error (99.9%) |
|-----------:|-------------------:|--------------:|
| 10         | 434                | ± 1,340       |
| 50         | 195                | ± 623         |
| 200        | 99                 | ± 345         |

### Poller Throughput — `claimAndMarkDone`

| Batch Size | Throughput (ops/s) | Error (99.9%) |
|-----------:|-------------------:|--------------:|
| 10         | 599                | ± 2,021       |
| 50         | 272                | ± 801         |
| 200        | 125                | ± 422         |

## Sample Results (MySQL 9.6, JDK 21)

> Single-threaded, 2s warmup x 2, 2s measurement x 3, 1 fork. MacBook environment with local MySQL (HikariCP, max 10) — numbers are indicative, not authoritative.

### Write Throughput

| Payload Size | Throughput (ops/s) | Error (99.9%) |
|-------------:|-------------------:|--------------:|
| 100 B        | 3,622              | ± 2,560       |
| 1,000 B      | 3,211              | ± 401         |
| 10,000 B     | 868                | ± 6,430       |

### Dispatch Latency

| Payload Size | Avg Latency (µs/op) | Error (99.9%) |
|-------------:|--------------------:|--------------:|
| 100 B        | 9,563               | ± 1,247       |
| 1,000 B      | 9,696               | ± 2,182       |
| 10,000 B     | 9,860               | ± 1,101       |

### Poller Throughput — `pollAndMarkDone`

| Batch Size | Throughput (ops/s) | Error (99.9%) |
|-----------:|-------------------:|--------------:|
| 10         | 229                | ± 1,167       |
| 50         | 74                 | ± 302         |
| 200        | 17                 | ± 46          |

### Poller Throughput — `claimAndMarkDone`

| Batch Size | Throughput (ops/s) | Error (99.9%) |
|-----------:|-------------------:|--------------:|
| 10         | 413                | ± 137         |
| 50         | 103                | ± 154         |
| 200        | 26                 | ± 31          |

## Sample Results (PostgreSQL, JDK 21)

> Not yet collected. Run with `-p database=postgresql` and update this section.

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
