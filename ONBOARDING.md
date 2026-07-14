# ONBOARDING — Predicta API (backend Java)

> Backend Spring Boot / Java 21 du projet Predicta : tableau de bord + carte live de la
> congestion routière d'Antananarivo. **Objectif produit : prédire les embouteillages.**

---

## 1. Ce que fait ce repo

Service Spring Boot 3.2 déployé en **AWS Lambda** (derrière API Gateway HTTP API v2) via
`aws-serverless-java-container`. Aujourd'hui il expose **un endpoint métier** : `GET /traffic`,
qui récupère en live les tuiles vectorielles (MVT `.pbf`) d'une source trafic externe couvrant
Tana, les convertit en GeoJSON et les fusionne en une seule `FeatureCollection`. **Aucune
persistence pour l'instant** : c'est un passthrough best-effort en temps réel.

Là où on va : transformer ce flux live en **pipeline de prédiction** (scraping périodique →
historique en base → agrégats par quartier → modèle ML court terme). Voir §6.

```
Source trafic externe (tuiles MVT z=13)
        │  fetch live (parallèle, best-effort)
        ▼
  /traffic ──► FeatureCollection GeoJSON  (← état actuel : live, sans mémoire)
        │
        ╎  ce qui manque (§6) :
        ▼
  cron scraper ──► Postgres (snapshots) ──► agrégats /quartier ──► ML ──► /predict
```

---

## 2. Architecture & composants importants

Pipeline trafic sous `services/traffic/`, découpé hexagonal (chaque étape derrière une interface,
mockable en test) :

| Composant | Rôle | Fichier |
|---|---|---|
| `TileGridSource` (iface) | liste des tuiles à couvrir | `TileGridSourceCentered` : grille carrée `(2·radius+1)²` autour d'un centre fixe (lon/lat/zoom configurables) |
| `TileFetcher` (iface) | récupère les octets MVT d'une tuile | `TileFetcherHttp` : `RestTemplate`, gunzip auto si magic bytes `1F 8B` |
| `MvtToGeoJsonConverter` | décode le flux de commandes MVT (zigzag + delta, MoveTo/LineTo), reprojette pixel→WGS84, ne garde que le layer `speeds` | `MvtToGeoJsonConverter.java` (le cœur, ~186 LOC) |
| `TrafficService` | orchestration : grille → fetch **parallèle** → convert par tuile → merge GeoJSON. Best-effort (une tuile en échec = ignorée + `partial=true`) | `TrafficService.java` |
| `TrafficController` | `GET /traffic`, pose l'en-tête `X-Predicta-Partial` si résultat incomplet | `endpoint/mvt/TrafficController.java` |

**Modèle GeoJSON typé** (`services/traffic/geojson/`) — remplace la manipulation manuelle de
`ObjectNode`, sérialisé par Jackson : `GeoJsonFeatureCollection` → `GeoJsonFeature` →
`GeoJsonGeometry` (sealed : `LineString` / `MultiLineString`) + `SpeedFeatureProperties`
(`speed` km/h, `rate` = ratio congestion).

**Entrées runtime** (un seul Spring, deux boots) :
- **Lambda (prod)** : `handler/LambdaHandler` — `RequestStreamHandler`, contexte Spring monté
  au cold start dans un bloc statique.
- **Local** : `PojaApplication.main` via `./gradlew bootRun`.

**Scaffold POJA** — tout ce qui porte `@PojaGenerated` (`mail/`, `file/`, `concurrency/`,
`datastructure/`, contrôleurs health, conf) est **généré par le template** et régénéré au déploiement.
Ne pas refactorer : c'est mort fonctionnellement mais revient tout seul. Le code métier (le pipeline
trafic) n'est **pas** marqué `@PojaGenerated`.

---

## 3. Stack

- **Java 21**, Spring Boot 3.2, Gradle.
- Persistence : JPA + Hibernate sur **PostgreSQL**, `ddl-auto=update` (schéma auto-dérivé des
  `@Entity`, **pas de tool de migration**).
- MVT : `com.wdtinc:mapbox-vector-tile` + `protobuf-java` (décodage manuel, pas de lib haut niveau).
- Déploiement : AWS Lambda + `aws-serverless-java-container`.
- API doc : springdoc → Swagger UI sur `/swagger-ui.html`, spec `doc/api.yml`.
- Tests : JUnit 5, Mockito, **Testcontainers** (Docker requis pour l'intégration), Jacoco
  (gate à 0 % — n'échoue jamais, affiche juste le taux).
- Lombok partout (`@RequiredArgsConstructor`, `@Builder`, `@Slf4j`).

---

## 4. Setup / premiers pas

Prérequis : **JDK 21**, **Docker** (tests d'intégration), un Postgres local.

```sh
# Postgres local attendu : jdbc:postgresql://localhost:5432/predicta, user postgres
./gradlew build        # compile + tests + jacoco
./gradlew test         # tous les tests (forks parallèles)
./gradlew bootRun      # lance en local sur :8080
./format.sh            # google-java-format --replace sur tout src/**/*.java
```

Test unique : `./gradlew test --tests com.predicta.mg.services.traffic.TrafficServiceTest`

Vérifier que ça tourne : `curl localhost:8080/ping` → `pong`, puis `curl localhost:8080/traffic`.

**Config** (`application.properties`, tout surchargeable par variable d'env) :
`scrape.center-lon/-lat`, `scrape.zoom`, `scrape.radius` (surface couverte),
`scrape.fetch-parallelism` (taille du pool de fetch), `scrape.tile-template` (URL des tuiles),
`DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD`.

---

## 5. Comment contribuer

1. **Brancher depuis `preprod`** (branche principale pour les PR). Branches `feat/...`, `fix/...`.
2. **Avant de committer** : `./gradlew build` doit passer + `./format.sh`.
3. **Conventions de code** :
   - Commentaires et logs **en français** (convention du repo — la garder).
   - Injection par constructeur (`@RequiredArgsConstructor`), pas de `@Autowired` sur champ.
   - Chaque étape I/O derrière une **interface** + une impl `*Http` / `*Centered`, pour rester
     testable sans réseau (regarder `TrafficServiceTest` : tout est mocké).
   - DTO/records de sortie typés + Jackson, **jamais** de `ObjectNode` à la main.
   - Ne pas toucher au scaffold `@PojaGenerated` (régénéré au déploiement).
4. **Tests** : toute logique non triviale (parser, branche, calcul) laisse au moins un test. Le
   pipeline trafic se teste sans réseau (tuiles MVT construites en mémoire dans les tests existants).
5. **Ne pas renommer** `settings.gradle` / l'artefact Lambda (`predicta-8f05e2da`) : identité de
   déploiement POJA.

---

## 6. Plan d'implémentation backend — vers la prédiction d'embouteillages

But final : un endpoint qui répond *« dans quel état sera le trafic du quartier X dans 15–30 min ? »*.
Trois briques manquent, dans cet ordre (chacune débloque la suivante — la prédiction n'existe pas
sans historique).

### Phase 1 — Traffic par quartier en temps réel (endpoint manquant)

`GET /traffic` renvoie aujourd'hui des **segments** bruts. Le produit a besoin d'un état **agrégé
par quartier**, lisible directement par la carte.

- **Données quartiers** : table `quartier` (id, nom, polygone). Charger une fois les polygones
  (source OSM ou fichier fourni). Tana ≈ quelques dizaines de quartiers.
- **Agrégation** : pour chaque segment GeoJSON du live, déterminer le quartier le contenant
  (point-in-polygon sur un point du segment, ex. milieu), puis agréger `speed`/`rate`
  (moyenne pondérée par longueur de segment) → un indice de congestion par quartier.
  - Pour rester simple au départ : index spatial en mémoire (bounding-box + test polygone), pas
    besoin de PostGIS tout de suite. À migrer vers PostGIS si la jointure devient le goulot.
- **Endpoint** : `GET /traffic/quartiers` → `FeatureCollection` de polygones quartiers + propriété
  `congestion` (ex. `rate` moyen, niveau `fluide|dense|saturé`).
- **Découpage hexagonal** (cohérent avec l'existant) :
  - `QuartierSource` (iface) + impl chargeant les polygones.
  - `QuartierAggregator` : `(List<GeoJsonFeature>, List<Quartier>) → List<QuartierTraffic>` (pur,
    testable sans réseau ni DB).
  - `QuartierTrafficController` → `GET /traffic/quartiers`.
- **Test clé** : segments connus + 2 polygones → vérifier l'affectation et l'indice agrégé.

### Phase 2 — Cron du scraper (persister l'historique)

La prédiction a besoin de **mémoire**. Aujourd'hui `/traffic` est purement live. Il faut capturer
l'état à intervalle régulier et le stocker.

- **Déclencheur** : en Lambda, un **EventBridge schedule** (toutes les 15–30 min) invoque un handler
  dédié (séparé du handler HTTP) ; le SDK `eventbridge` est déjà au classpath. En local, un
  `@Scheduled` Spring pour tester.
- **Job** : réutiliser `TrafficService.liveGeoJson()` (déjà tout fait : grille → fetch → merge),
  puis **persister** un snapshot horodaté.
- **Schéma** (rappel : `ddl-auto=update`, donc déclarer les `@Entity` suffit) :
  - `traffic_snapshot` (id, `captured_at`, `quartier_id`, `congestion_rate`, `avg_speed`,
    `nb_segments`). Stocker **agrégé par quartier** (sortie de la Phase 1), pas chaque segment :
    bien plus léger et c'est la granularité dont le ML a besoin.
  - Dédup : ignorer un snapshot identique au précédent (hash du contenu) pour ne pas gonfler la base.
- **Idempotence / best-effort** : un run raté ne casse rien, le suivant repart. Logguer `partial`.
- **Test clé** : job appelé → N lignes `traffic_snapshot` écrites avec le bon `captured_at`.

> Frontière : ce repo **lit le live + écrit ses propres snapshots agrégés**. Il possède toute sa
> donnée — il n'y a plus de pipeline de données séparé.

### Phase 3 — ML : prédire l'embouteillage

Une fois quelques jours d'historique par quartier accumulés.

- **Cible** : pour chaque quartier, prédire `congestion_rate` à +15 / +30 min.
- **Commencer bête et utile (baseline), surtout pas un gros modèle d'emblée** :
  - **Moyenne historique par (quartier, jour-de-semaine, heure)** + tendance récente (EMA des
    derniers snapshots). C'est un nowcast honnête, calculable en SQL/Java pur, **zéro dépendance ML**.
    Madagascar = UTC+3 fixe, pas de DST : dériver `heure_locale` / `jour_locale` directement.
  - Endpoint `GET /predict?quartier_id=…&horizon=30` renvoyant l'état prédit + un intervalle.
- **Itérer seulement si la baseline déçoit** : régression sur features (heure, jour, météo, état des
  quartiers voisins t-1). En Java pur d'abord (Smile/Tribuo) pour rester dans la Lambda ; n'externaliser
  vers un service Python que si un vrai modèle séquentiel (LSTM/GBT lourd) devient nécessaire — pas avant.
- **Découpage** : `Forecaster` (iface) — `forecast(quartierId, horizon) → Prediction` ; impl
  `BaselineForecaster` (moyenne + tendance) lisant `traffic_snapshot`. Le contrôleur ne dépend que de
  l'interface → swap du modèle sans toucher l'API.
- **Test clé** : historique synthétique avec un pic à 8h → la prédiction à 8h doit ressortir le pic.

### Ordre & dépendances

```
Phase 1 (agrégat quartier)  ──►  Phase 2 (cron persiste les agrégats)  ──►  Phase 3 (ML lit l'historique)
   /traffic/quartiers              traffic_snapshot                          /predict
```

Ne pas sauter d'étape : pas d'agrégat quartier propre → snapshots inexploitables → ML sur du bruit.
À chaque phase, livrer l'endpoint le plus simple qui marche, le mettre dans `doc/api.yml`, et
ajouter le test qui casse si la logique casse.
