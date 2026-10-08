# Java Load Balancer

A from-scratch **L4 (TCP) / L7 (HTTP) load balancer** in pure Java 21 (raw `java.net` sockets + virtual threads, **zero external dependencies**), with four pluggable routing algorithms, a per-client Token Bucket rate limiter, active health checks, Prometheus metrics and a provisioned Grafana dashboard.

```
                       ┌─────────────────────────── Load Balancer ───────────────────────────┐
 clients ──► :8080 ──► │ L4Proxy / L7Proxy ─► RateLimiter ─► BackendPool ─► RoutingStrategy  │ ──► backend-1..5
                       │       │  (token bucket     (healthy list,       (RR | WRR | LC | CH) │
                       │       │   per client)       failover x3)                             │
                       │       ▼                                                              │
 Prometheus ◄─ :9100 ◄─│ AdminServer (/metrics /health /admin/backends /admin/algorithm)      │
       ▲               └─────────────────────────── HealthChecker (TCP) ──────────────────────┘
   Grafana :3000
```

## Quick start

**Docker (full stack: LB + 5 backends + Prometheus + Grafana)**

```bash
docker compose up --build -d
curl -H 'X-Client-Id: alice' localhost:8080/        # -> Hello from backend-N
open http://localhost:3000                          # Grafana dashboard (anonymous admin, "Load Balancer")
open http://localhost:9090                          # Prometheus
docker compose --profile bench run --rm benchmark   # run the benchmark inside the network
LB\_ALGORITHM=least\_connections docker compose up -d loadbalancer   # change algorithm (or use the admin API, below)
RATE\_LIMIT\_ENABLED=false docker compose up -d loadbalancer         # disable throttling for raw throughput runs
LB\_MODE=L4 docker compose up -d loadbalancer                       # switch to raw TCP proxying
```

**No Docker (needs JDK 21)**

```bash
scripts/build.sh \&\& scripts/test.sh     # compile + 31 tests
scripts/run-local.sh                    # 5 backends on :9001-9005 + LB on :8080 (admin :9100)
scripts/bench.sh --requests 5000        # benchmark every algorithm x scenario
scripts/stop-local.sh
```

## Routing algorithms (`lb.algo`)

All implement `RoutingStrategy { String name(); Backend select(List<Backend> healthy, String clientKey); }` and are registered in `Strategies` — adding a fifth is one class + one `register(...)` line. Switch at runtime: `curl 'localhost:9100/admin/algorithm?name=least\_connections'`.

|Name|Behaviour|
|-|-|
|`round\_robin`|Atomic counter modulo healthy backends|
|`weighted\_round\_robin`|Smooth WRR (nginx-style): exact weight ratios, picks interleaved rather than bursty|
|`least\_connections`|Lowest `active / weight`; ties rotate|
|`consistent\_hash`|MD5 ring, 150 virtual nodes × weight; same client key → same backend; removing a node only remaps *its* keys|

Client key = `X-Client-Id` header (L7) or client IP (L4/L7 fallback).

## Rate limiter (`lb.ratelimit`)

Token bucket per client (`capacity` = burst, `refill/sec` = sustained rate). L7 answers `429` + `Retry-After`; L4 closes the connection. Per-client overrides: `LB\_RATE\_LIMIT\_OVERRIDES="vip=500:1000,scraper=2:5"` (`refill:capacity`). Idle buckets are evicted every minute. Unit tests drive it with an injected fake clock, including a 1,500-request simulation and a 5,000-request concurrency test.

## Configuration (env vars)

|Variable|Default||
|-|-|-|
|`LB\_MODE`|`L7`|`L4` or `L7`|
|`LB\_PORT` / `LB\_ADMIN\_PORT`|`8080` / `9100`||
|`LB\_ALGORITHM`|`round\_robin`|see above|
|`LB\_BACKENDS`|3× localhost|`host:port\[:weight],...`|
|`LB\_RATE\_LIMIT\_ENABLED` / `\_CAPACITY` / `\_REFILL\_PER\_SEC` / `\_OVERRIDES`|`true` / `100` / `50` / –||
|`LB\_HEALTH\_INTERVAL\_MS`, `LB\_CONNECT\_TIMEOUT\_MS`, `LB\_IDLE\_TIMEOUT\_MS`|`2000`, `2000`, `30000`||

## Metrics (16 families at `:9100/metrics`)

`lb\_connections\_total`, `lb\_requests\_in\_flight`, `lb\_rate\_limited\_total`, `lb\_no\_backend\_available\_total`, `lb\_bytes\_received\_total`, `lb\_bytes\_sent\_total`, `lb\_http\_responses\_total{code}`, `lb\_rate\_limiter\_tracked\_clients`, `lb\_uptime\_seconds`, `lb\_info{algorithm,mode}`, and per-backend: `lb\_backend\_requests\_total`, `lb\_backend\_errors\_total`, `lb\_backend\_active\_connections`, `lb\_backend\_up`, `lb\_backend\_weight`, `lb\_request\_duration\_seconds` (histogram → p50/p95/p99).
The Grafana dashboard (17 panels) includes a live weight-adjusted **Jain fairness** gauge.

## Benchmarking (`lb.bench.Benchmark`)

Runs *algorithms × scenarios* (`uniform` clients; `hotspot` = 80% of traffic from 5 clients), reporting throughput, p50/p95/p99, 429 count, per-backend distribution and **Jain's fairness index** (1.0 = load exactly proportional to weight; pass `--weights` for weight-aware scoring). Writes a CSV.
Sample run (single laptop-class sandbox, 5 backends weighted 3/2/1/1/1, backend-5 slower; your numbers will differ):

|algorithm|scenario|req/s|p95 ms|Jain|
|-|-|-|-|-|
|weighted\_round\_robin|uniform|1503|60.6|1.000|
|least\_connections|uniform|1773|51.8|0.927|
|consistent\_hash|uniform|1721|58.4|0.981|
|consistent\_hash|hotspot|1623|58.1|0.741|

Takeaways: WRR is perfectly fair by construction; least-connections shifts load away from the slow backend (lower latency, lower "fairness" by design); consistent hashing is sticky but degrades under hot keys.

## Tests

`scripts/test.sh` — 31 dependency-free tests: algorithm correctness (exact WRR ratios, minimal key remapping on node removal…), token-bucket maths, and **end-to-end tests over real sockets** (L7 + L4, failover, recovery, 503 with no backends, 429 + Retry-After, POST bodies, concurrency, Prometheus format validation, runtime algorithm switching).

## Design notes / known limits

* L7 handles **one request per client connection** (`Connection: close` both ways) — simple and robust, but no keep-alive/HTTP2/WebSocket/TLS termination and no chunked *request* bodies (→ 501). Chunked *responses* are relayed untouched.
* Failover: connect failures retry up to 3 distinct backends and mark the failed one down until the TCP health check passes again. Requests that fail *after* connecting are not retried (not generally safe for non-idempotent calls).
* Least-connections counts connections the LB itself opened, so it is per-LB-instance state.

