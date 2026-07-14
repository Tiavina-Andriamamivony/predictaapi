# Predicta API

Real-time traffic congestion API for Antananarivo, Madagascar. Fetches live Mapbox Vector Tiles from an external traffic source, converts them to GeoJSON on the fly, and enriches them with OpenStreetMap quartier and road data. This is the **only backend** for Predicta.

## Tech Stack

- **Java 21** / **Spring Boot 3.2**
- **PostgreSQL** — JPA + Hibernate, Flyway migrations
- **AWS Lambda** (API Gateway HTTP API v2) via `aws-serverless-java-container`
- **JUnit 5** + Testcontainers (Docker required for integration tests)
- **Lombok** throughout

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/traffic` | API key | Live Antananarivo traffic as a GeoJSON FeatureCollection |
| `GET` | `/quartiers?q=` | API key | Search Tana quartiers by name substring (returns name + centroid) |
| `GET` | `/ping` | Open | Healthcheck — returns `"pong"` |
| `GET` | `/health/email?to=` | Open | Send 5 test emails to verify the SES pipeline |

**API key:** pass `X-API-Key` header. Keys are stored in the `applications` table.

**Swagger UI:** `/swagger-ui.html` — **OpenAPI spec:** `/v3/api-docs`

## Traffic Pipeline

```
TileGridSource (disk grid around Tana center)
  → TileFetcherHttp (parallel fetch, auto-gunzip)
  → MvtToGeoJsonConverter (hand-decoded MVT geometry → WGS84)
  → OsmEnricher (adds quartierId + fills road names, best-effort)
  → merged GeoJSON FeatureCollection
```

- **Zero persistence** — traffic is fetched and converted live, never stored.
- **Best-effort** — failed tiles are skipped (warn log), `X-Predicta-Partial: true` header is set.
- The `speeds` MVT layer is kept: each feature has `name`, `quartierId`, `speed` (km/h), and `rate` (congestion ratio).

## Build / Run

```sh
./gradlew build                 # compile + test + jacoco verify
./gradlew test                  # run all tests (JUnit 5, parallel forks)
./gradlew test --tests com.predicta.mg.conf.FacadeIT   # single test class
./gradlew bootRun               # run locally (needs PostgreSQL on localhost:5432)
./format.sh                     # google-java-format --replace over all src/**/*.java
```

**Prerequisites:** JDK 21, Docker (for Testcontainers), PostgreSQL.

## Project Structure

```
src/main/java/com/predicta/mg/
  PojaApplication.java                 # Spring Boot entry point
  handler/LambdaHandler.java           # AWS Lambda entry point
  endpoint/
    mvt/TrafficController.java         # GET /traffic
    mvt/QuartierController.java        # GET /quartiers
    rest/controller/health/            # GET /ping, GET /health/email
    ApiKeyConfigurer.java              # API key interceptor
  models/
    Quartier.java                      # JPA entity (quartiers table)
    Application.java                   # JPA entity (applications table)
    TileCoordinate.java                # record(zoom, tileX, tileY)
    TileGridSource.java                # Interface — disk grid generation
    TileFetcher.java                   # Interface — tile HTTP fetch
    TrafficResult.java                 # record(featureCollection, partial)
  repository/                          # Spring Data JPA repos
  services/
    QuartierService.java               # Quartier search
    traffic/
      TrafficService.java              # Orchestration
      MvtToGeoJsonConverter.java       # MVT protobuf → GeoJSON decoder
      geojson/                         # Typed GeoJSON records
      osm/                             # OSM enrichment (Overpass, R-tree)
  conf/                                # RestTemplate, OpenAPI beans
  mail/                                # SES email scaffold (POJA-generated)

src/main/resources/
  application.properties
  db/migration/
    V1__quartiers.sql                  # 372 Tana quartiers (OSM centroids)
    V2__applications.sql               # API key auth table

doc/
  api.yml                              # OpenAPI 3.0.3 spec
```

## Persistence

- **Flyway** owns the schema — migrations run at boot before Hibernate.
- `V1__quartiers.sql` seeds 372 Antananarivo quartiers (OSM admin polygons + Voronoi cells), keeping only name + centroid.
- `V2__applications.sql` creates the `applications` table for API key auth.
- `ddl-auto=update` is on — `@Entity` classes must match Flyway-created tables exactly.
- **Traffic is never persisted.**

## License

[MIT](LICENSE)
