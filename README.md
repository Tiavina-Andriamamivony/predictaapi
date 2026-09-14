<div align="center">

<img src="docs/img/logo.png" alt="Predicta" width="96" height="96" />

# Predicta API

**Real-time road-traffic API for Antananarivo, Madagascar.**

[![CI](https://github.com/Tiavina-Andriamamivony/predictaapi/actions/workflows/ci.yml/badge.svg)](https://github.com/Tiavina-Andriamamivony/predictaapi/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

</div>

## What is Predicta

Predicta serves **live road-congestion data for Antananarivo** as GeoJSON. It fetches Mapbox
Vector Tiles from an external traffic source, decodes and reprojects them to WGS84 on the fly, and
enriches each road segment with its OpenStreetMap quartier and name — **no traffic is ever stored**.
A companion quartier search lets clients recenter the map on any of the city's 372 neighbourhoods.

This is the **only backend** for Predicta. It ships as a single Docker image and runs as one
512 MB web service on **Render** — see [Deployment](#deployment).

<div align="center">
<img src="docs/img/screenshot.png" alt="Predicta live traffic map of Antananarivo" width="760" />
</div>

## Where to get it

```sh
git clone git@github.com:Tiavina-Andriamamivony/predictaapi.git
cd predictaapi
./gradlew bootRun    # runs on http://localhost:8080 (needs PostgreSQL, see below)
```

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/traffic` | API key | Live Antananarivo traffic as a GeoJSON `FeatureCollection` (whole city, heavy) |
| `GET` | `/traffic/quartier/{quartierId}` | API key | Live traffic of one quartier — OSM polygon (or Voronoi cell) grid (1-4 tiles) + geometric filter, 45 s in-memory cache, `404` if unknown |
| `PUT` | `/traffic/zone` | API key | Traffic around a centroid as GeoJSON (lightweight disk grid, for map recentering) |
| `GET` | `/traffic/tile/{z}/{x}/{y}.mvt` | Open (CORS `*`) | Raw MVT tile passthrough — what MapLibre fetches itself. No conversion, no enrichment, `Cache-Control` + strong `ETag` (304). The memory-cheap way to serve the map |
| `GET` | `/quartiers?q=` | API key | Search Tana quartiers by name substring (returns name + centroid) |
| `GET` | `/ping` | Open | Healthcheck — returns `"pong"` |
| `GET` | `/health/email?to=` | Open | Send test emails to verify the SES pipeline |

- **Auth:** pass your key in the `X-API-Key` header. Keys live in the `applications` table.
- **Docs:** Swagger UI at `/swagger-ui.html`, OpenAPI spec at `/v3/api-docs` (also `doc/api.yml`).

## How traffic works

```
TileGridSourceCentered   disk grid of tiles around the Tana center (radius in tiles)
        │
TileFetcherHttp          fetch each .pbf in parallel, auto-gunzip
        │
MvtToGeoJsonConverter    hand-decode the MVT geometry stream → WGS84, keep the "speeds" layer
        │
OsmEnricher              add quartierId + fill road names (best-effort)
        │
        ▼
   GeoJSON FeatureCollection   (streamed out gzipped — ~75 MB of raw JSON on a 512 MB heap)
```

Each `speeds` feature carries `name`, `quartierId`, `speed` (km/h) and `rate` (congestion ratio).
The pipeline is **best-effort**: a tile that fails to fetch is skipped (warn log) and the response
sets `X-Predicta-Partial: true`. The tile-URL template is **not committed** — supply it via the
`SCRAPE_TILE_TEMPLATE` env var (placeholders `{x} {y} {zoom}`).

`/traffic/zone` and `/traffic/quartier/{quartierId}` reuse the same pipeline on much smaller grids:
a centroid disk (`scrape.zone-radius`, 2 tiles by default) or the quartier's geometry — the OSM
polygon when available (`rel_*`), otherwise a Voronoi cell rebuilt from the stored centroids, so
all 372 quartiers are covered (typically 1-4 tiles) — then a geometric filter keeps only the
segments inside the quartier. Quartier responses are cached in memory (45 s, stale-while-revalidate,
age exposed via `X-Predicta-Age`); if no geometry is available the endpoint degrades to the
unfiltered centroid disk and signals it via `X-Predicta-Fallback`. `/traffic` stays for whole-city
views.

### Memory budget

Whole-city `/traffic` is the expensive path: it materializes a ~75 MB GeoJSON object graph. Three
invariants keep that from filling the 512 MB container:

- the gzip filter compresses **straight to the response stream** (`GzipResponseFilter`), so the body
  is only ever materialized once instead of being copied twice — those two copies were the OOM, and
  `GzipResponseFilterTest` locks in the one-pass contract;
- the tile fetch pool is **shared and bounded**, so N concurrent requests no longer mean 16×N threads
  and 16×N tiles in flight;
- the quartier cache is **bounded** and evicts the oldest entry (32 by default) instead of keeping
  every quartier forever.

`PowerOfTenRulesTest` (ArchUnit) fails the build if a service ever creates its own pool again.

Serving the map through `/traffic/tile/**` skips the object graph entirely: that is the cheap path,
and the one the frontend should prefer.

## Build and run

```sh
./gradlew build                 # compile + test + checkstyle + jacoco verify
./gradlew test                  # run all tests (JUnit 5, parallel forks)
./gradlew test --tests com.predicta.mg.services.traffic.TrafficServiceTest   # a single test class
./gradlew bootRun               # run locally (needs PostgreSQL on localhost:5432, db=predicta)
./format.sh                     # google-java-format over all src/**/*.java
```

**Prerequisites:** JDK 21 and a PostgreSQL database for `./gradlew bootRun`. Docker is only needed
to build or run the container image (the test suite runs without it).

## Deployment

Production is a **single Docker container on Render** (512 MB plan). Render builds the
[`Dockerfile`](Dockerfile) from the branch it tracks and starts it through
[`docker-entrypoint.sh`](docker-entrypoint.sh), which translates Render's environment into Spring
Boot configuration:

| Concern | How it works |
|---|---|
| **Port** | Render sets `PORT` (10000 on the current service) and the entrypoint passes it as `server.port`. The `EXPOSE 8080` in the Dockerfile is only the local default — it is *not* the port Render connects to, and a health check on 8080 will fail there. |
| **Database URL** | Render injects `postgres://user:pass@host/db`; the entrypoint rewrites the scheme to `jdbc:postgresql://…` as `SPRING_DATASOURCE_URL`. A URL that is already JDBC is left untouched. |
| **Memory** | `-XX:MaxRAMPercentage=75` (≈384 MB of heap on this plan) plus `-XX:+ExitOnOutOfMemoryError`: a heap OOM exits the process so Render restarts it cleanly instead of leaving it hung. |
| **Health check** | `GET /ping` — no API key, so it is safe to point Render's check at it. |

| Environment variable | Purpose |
|---|---|
| `DATABASE_URL` (+ `DATABASE_USERNAME` / `DATABASE_PASSWORD`) | PostgreSQL connection |
| `SCRAPE_TILE_TEMPLATE` | Upstream MVT URL template (`{x} {y} {zoom}`). **Required in practice** — without it the app boots, but `/traffic` answers with nothing useful |
| `PORT` | Injected by Render; do not set it by hand |
| `JAVA_OPTS` | Optional extra JVM flags |
| `SCRAPE_*`, `SPRING_JPA_SHOW_SQL` | Optional tuning; every key and its default is in `src/main/resources/application.properties` |

Run the image locally:

```sh
docker build -t predicta .
docker run --rm -p 8080:8080 \
  -e DATABASE_URL="postgres://postgres:postgres@host.docker.internal:5432/predicta" \
  -e SCRAPE_TILE_TEMPLATE="https://…/{zoom}/{x}/{y}.pbf" \
  predicta
```

The POJA **AWS Lambda entry point** (`handler/LambdaHandler.java`) and the `cd-compute.yml` workflow
(SAM build + Poja trigger) are still in the repo from the starter template — the Render container is
the production path.

## Code quality — the Power of Ten

The codebase follows the JPL/NASA **Power of Ten** rules, enforced in CI rather than by convention:

```sh
./gradlew checkstyleMain checkstyleTest         # rules 1, 2, 4, 6, 7, 8, 9 (source)
./gradlew test --tests "*PowerOfTenRulesTest"   # rules 3, 6, 9 (bytecode, via ArchUnit)
```

`-Xlint:all -Werror` is on for both main and test sources — a warning fails the build. The
rule-to-tool mapping, the deliberate deviations and how to run each check are in
[docs/power-of-ten.md](docs/power-of-ten.md).

## Persistence

- **Flyway** owns the schema — migrations run at boot before Hibernate.
- `V1__quartiers.sql` seeds 372 Antananarivo quartiers (OSM admin polygons + Voronoi cells), keeping only name + centroid.
- `V2__applications.sql` creates the `applications` table for API-key auth.
- `ddl-auto=update` is on — `@Entity` classes must match the Flyway-created tables exactly.
- On Render the connection string arrives as `DATABASE_URL` (`postgres://…`) and is converted to JDBC
  by `docker-entrypoint.sh`.
- **Traffic itself is never persisted.**

## Project structure

```
src/main/java/com/predicta/mg/
  PojaApplication.java              Spring Boot entry point
  handler/LambdaHandler.java        AWS Lambda entry point (POJA scaffold — not used on Render)
  endpoint/mvt/                     GET /traffic, GET /quartiers
  models/                           TileGridSourceCentered, TileFetcherHttp, records
  services/traffic/                 TrafficService + MvtToGeoJsonConverter (+ geojson/, osm/)
  conf/                             RestTemplate, gzip filter, ScrapeProps, OpenAPI
src/main/resources/
  application.properties
  db/migration/                     V1__quartiers.sql, V2__applications.sql
doc/api.yml                         OpenAPI 3.0.3 spec
Dockerfile                          multi-stage build → JRE 21 image, runs on Render
docker-entrypoint.sh                PORT + DATABASE_URL adaptation, JVM memory flags
```

## Contributing

New here? Start with [ONBOARDING.md](ONBOARDING.md). Code comments and logs are in French — keep
that convention. Run `./format.sh` and `./gradlew build` before opening a PR.

## License

[MIT](LICENSE.txt) © Predicta
