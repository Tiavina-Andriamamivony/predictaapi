# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot 3.2 / Java 21 service scaffolded from a **POJA** (Pieces Of Java App) starter template. Deploys as an **AWS Lambda** behind API Gateway (HTTP API v2) via `aws-serverless-java-container`. **This is the only backend for Predicta.** It serves live Antananarivo traffic (Mapbox Vector Tiles fetched, converted to GeoJSON on the fly, no persistence) plus a quartier search to recenter the map.

## Build / test / run

```sh
./gradlew build                 # compile + test + jacoco verify
./gradlew test                  # run all tests (JUnit 5, parallel forks)
./gradlew test --tests com.predicta.mg.conf.FacadeIT   # single test class
./gradlew bootRun               # run locally (needs Postgres on localhost:5432, db=predicta)
./format.sh                     # google-java-format --replace over all src/**/*.java
```

- Java 21 required. Jacoco runs as a `finalizedBy` on `test`; coverage minimum is set to 0, so the gate never fails the build — it only prints the line-coverage rate.
- `**/gen/**` is excluded from coverage (OpenAPI-generated code).
- Tests use Testcontainers (`org.testcontainers`) — Docker must be running for integration tests.

## Runtime entrypoints

Two ways the same Spring app boots, sharing one `PojaApplication`:

- **Lambda (prod):** `handler/LambdaHandler` is the `RequestStreamHandler`. It statically builds a `SpringBootLambdaContainerHandler` for `HttpApiV2ProxyRequest` and proxies the stream. Cold start initializes the full Spring context in the static block.
- **Local:** `PojaApplication.main` via `bootRun`.

`@PojaGenerated` marks template/scaffold code (handler, app, conf, health controllers). Treat annotated classes as generated — prefer not editing them unless changing scaffold behavior.

## Persistence

JPA + Hibernate against a **dedicated** PostgreSQL (separate from any other Predicta DB — there is no shared DB anymore). Schema is owned by **Flyway**: migrations live in `src/main/resources/db/migration/V*__*.sql` and run at boot before Hibernate. `ddl-auto=update` is still on, so `@Entity` classes must match the Flyway-created tables exactly (otherwise Hibernate alters them). Connection defaults to `jdbc:postgresql://localhost:5432/predicta`, user `postgres`. Entities live in `models/`, Spring Data repos in `repository/`.

`V1__quartiers.sql` seeds the `quartiers` table: 372 Tana quartiers (OSM admin polygons + Voronoi cells on `place=*` nodes), keeping only **name + centroid**. `QuartierController` exposes `GET /quartiers?q=` (substring search, blank = all) returning `{name, lon, lat}` to recenter the map. No PostGIS — add `geom(Polygon,4326)` + `ST_Contains` only when traffic must be clipped per-quartier server-side.

## Live traffic pipeline (the active feature)

`GET /traffic` (`endpoint/mvt/TrafficController`) is a **live, zero-persistence** passthrough. `TrafficService.liveGeoJson()` orchestrates: `TileGridSource` builds the tile grid covering Tana (centre + square radius, `scrape.*` props) → `TileFetcher` fetches each `.pbf` in parallel (pool size `scrape.fetch-parallelism`, gzip-decompressed if magic bytes `1F 8B`) → `MvtToGeoJsonConverter` hand-decodes the MVT geometry command stream (zigzag + delta; cmd `1`=MoveTo, `2`=LineTo, `7`=ClosePath), keeps only the `speeds` layer, reprojects tile pixels back to WGS84 → merged into one GeoJSON `FeatureCollection`. Best-effort: a failed tile is skipped (warn log) and sets `X-Predicta-Partial: true`.

The MVT tile-URL template is **not** in the repo — it must be supplied via the `SCRAPE_TILE_TEMPLATE` env var (placeholders `{x} {y} {zoom}`). With it unset the app still boots, but `/traffic` returns nothing useful. MVT decoding is done manually against `com.wdtinc:mapbox-vector-tile`'s generated `VectorTile` protobuf classes, not a high-level library. The `RestTemplate` bean is wired in `conf/RestTemplateConf`.

## OpenAPI

`org.openapi.generator` plugin (7.7.0) is on the classpath and `doc/api.yml` documents the traffic GeoJSON schema. No `GenerateTask` is configured yet, so no client/server code is generated. `.shell/publish_gen_to_maven_local.sh` publishes generated artifacts to the local Maven repo when that pipeline is set up.

## Conventions

- Lombok throughout: `@RequiredArgsConstructor` constructor injection, `@Slf4j` logging.
- Code comments and logs are written in French; keep that convention when editing this feature's files.
- `settings.gradle` / Lambda artifact names embed a generated suffix (`predicta-8f05e2da`) — POJA deployment identity, don't rename.
