# Design — `/traffic` live : merge fiable de N tuiles MVT → un GeoJSON couvrant tout Tana

Date : 2026-06-09
Statut : proposé

## Problème

`GET /traffic` doit renvoyer, à l'instant t, **un seul FeatureCollection GeoJSON** couvrant
tout Antananarivo, construit en fusionnant plusieurs tuiles MVT (`.pbf`) de
`traffic.tag-ip.com`. Deux défauts dans l'implémentation actuelle l'empêchent :

1. **Merge protobuf cassé.** `MvtScraperService.merge()` concatène les `layers` de chaque
   tuile dans un seul `VectorTile.Tile`. Or les coordonnées MVT sont des **pixels relatifs à
   chaque tuile XYZ**. À la conversion, `MvtToGeoJsonService.convert()` applique le `tileX/tileY`
   de la **première** tuile (`getSourceTiles().getFirst()`) à **toutes** les features. Résultat :
   les features des autres tuiles sont reprojetées avec de mauvaises coordonnées de tuile et se
   superposent sur la première. Faux dès qu'il y a plus d'une tuile à des positions différentes.

2. **Une seule tuile fetchée par URL.** Le code dérive 1 tuile du centre `#map=z/x/y` de chaque
   URL. Or à l'écran le navigateur charge une **grille** de tuiles autour du centre. Les 2 URLs
   fournies pointent vers z13 x=5177 y=4535 (bas) et y=4532 (haut) — non adjacentes, trou de 2
   tuiles entre elles. Deux tuiles seules ne couvrent donc pas tout Tana.

## Décisions

- **Emprise.** Bbox englobant les centres des 2 URLs → grille **z13, x=5177, y=4532..4535**
  (4 tuiles, contiguës, lat −18.979..−18.813, lon 47.505..47.549). Sans trou. La grille est
  **dérivée d'une configuration** (liste de centres `#map` + zoom), pas hardcodée — extensible à
  d'autres colonnes x si besoin plus tard.
- **Merge fiable = merge GeoJSON, pas merge protobuf.** Chaque tuile est convertie
  **indépendamment avec son propre `tileX/tileY/zoom`** en un FeatureCollection partiel (coords
  déjà en lon/lat WGS84 absolu), puis on **concatène les `features[]`**. Additionnables sans
  risque car absolues. C'est la correction du défaut n°1.
- **Live, zéro persistence.** Chaque `GET /traffic` refetch les tuiles depuis TAG-IP et renvoie
  le GeoJSON. Rien n'est écrit en base sur ce chemin.
- **Politique d'échec : best-effort.** Si une tuile échoue (timeout, réponse vide), on renvoie le
  GeoJSON des tuiles réussies, on log un warn, et on pose le header `X-Predicta-Partial: true`.
  `/traffic` reste utilisable même si TAG-IP a un hoquet. (Le futur cron de persistence utilisera
  la même brique fetch mais en **tout-ou-rien** avant d'écrire, pour ne jamais persister un
  snapshot partiel — un trou honnête vaut mieux qu'un snapshot mensonger.)
- **Pas de déduplication.** Concat brut des features. La source contient déjà des doublons
  (segment A→B et B→A) ; on reste fidèle à la source.
- **Clean code : pur séparé de l'I/O.** Les briques fetch/convert/merge sont des composants purs
  à responsabilité unique, sans dépendance BDD ni Spring Data, testables sans Docker. La
  persistence reste un détail d'infra porté par le futur cron, pas par le chemin live.

## Architecture

Flux `GET /traffic` :

```
TileGridSource (config → List<TileCoordinate>)
        │
        ▼
TrafficService.liveGeoJson()
   pour chaque tuile (en parallèle, best-effort) :
        TileFetcher.fetch(coord)         → byte[] MVT décompressé   (peut échouer → skip)
        MvtToGeoJsonConverter.convert(coord, bytes) → FeatureCollection partiel (lon/lat absolu)
   GeoJsonMerger.merge(List<FeatureCollection>) → FeatureCollection unique
        │
        ▼
TrafficController → 200 + application/geo+json   (+ X-Predicta-Partial: true si incomplet)
```

### Composants (tous purs sauf TileFetcher qui fait l'I/O réseau)

- **`TileCoordinate`** (record, déjà existant : `zoom, tileX, tileY`). Réutilisé.
- **`TileGridSource`** — interface ; lit la config (centres `#map` + zoom), décode chaque centre
  EPSG:3857→WGS84→tuile XYZ, calcule la bbox englobante et génère **toutes** les tuiles z du
  rectangle (sans trou). Renvoie `List<TileCoordinate>` dédupliquée.
  - Entrée : config. Sortie : liste de tuiles. Dépendances : aucune (math pure).
- **`TileFetcher`** — interface ; `byte[] fetch(TileCoordinate)`. Impl `TileFetcherHttp` : applique
  le template d'URL, `RestTemplate.exchange`, gunzip si magic bytes `1F 8B`. Lève une exception
  vérifiable en cas d'échec. Seul composant à faire de l'I/O.
- **`MvtToGeoJsonConverter`** — pur ; `ObjectNode convert(TileCoordinate, byte[] mvt)`. Décode le
  protobuf et le command stream (MoveTo/LineTo/ClosePath, zigzag+delta), reprojette pixel→WGS84
  avec le `tileX/tileY/zoom` **de cette tuile**, renvoie un FeatureCollection. Reprend la logique
  de décodage existante de `MvtToGeoJsonService`, mais sans `@Transactional`, sans repo, sans
  memoïsation BDD, et **paramétrée par la coordonnée de tuile** (correction du défaut n°1).
- **`GeoJsonMerger`** — pur ; `ObjectNode merge(List<ObjectNode> collections)`. Crée un
  `FeatureCollection`, concatène tous les `features[]`. Aucune dédup.
- **`TrafficService`** — orchestre grid→fetch→convert→merge. Prend `TileGridSource`, `TileFetcher`,
  `MvtToGeoJsonConverter`, `GeoJsonMerger` en interfaces (constructeur, `@RequiredArgsConstructor`).
  Best-effort : try/catch par tuile, collecte les succès, expose un `boolean partial`. Renvoie un
  petit objet `{ ObjectNode featureCollection, boolean partial }`.
- **`TrafficController`** — `GET /traffic` ; appelle `TrafficService`, sérialise, pose
  `Content-Type: application/geo+json` et `X-Predicta-Partial` si partiel.

### Configuration (`application.properties`)

```
# Centres de vue OpenLayers (#map=zoom/xMerc/yMerc/rot), séparés par virgule
scrape.source-urls=${SCRAPE_SOURCE_URLS:...index.html#map=13/5290085.13/-2148941.17/0,...index.html#map=13/5289339.87/-2134112.38/0}
# Template tuile .pbf
scrape.tile-template=${SCRAPE_TILE_TEMPLATE:https://traffic.tag-ip.com/tile/v1/car/tile({x},{y},{zoom}).mvt}
```

La grille z13 x=5177 y=4532..4535 est **calculée** à partir de ces 2 centres (bbox englobante),
pas écrite en dur.

## Sortie attendue

FeatureCollection GeoJSON identique en forme à `src/test/java/com/predicta/mg/conf/result.geojson` :
features `LineString`/`MultiLineString`/`Point` ; `properties` portant au moins
`layer, speed, name, rate, weight, duration, datasource, is_small, is_startpoint` selon le layer
(`speeds`, `osmnodes`, `internal-nodes`). Coordonnées `[lon, lat]` WGS84 arrondies à 1e-7.

## Code existant

- `RestTemplate` bean : déjà fourni par `conf/RestTemplateConf`. Aucun câblage manquant.
- Entités/repos `MvtTile`, `MergedMvtTile`, `GeoJsonResult` : **conservés intacts** pour le futur
  cron de persistence. Non appelés par le chemin `/traffic` live.
- `MvtScraperService` / `MvtToGeoJsonService` actuels (decode + persist + `@Transactional`) : la
  logique de décodage est extraite vers `MvtToGeoJsonConverter` pur. Le chemin live ne les utilise
  plus ; ils restent disponibles pour le cron (qui ajoutera la couche persist par-dessus les
  briques pures).
- `TrafficController` actuel (qui appelle scrape+merge+convert persistants) : recâblé sur
  `TrafficService` live.

## Tests

Unitaires, sans Docker (composants purs) :

- **`MvtToGeoJsonConverter`** : tuile MVT minimale encodée à la main (1 LineString) → vérifier
  reprojection lon/lat avec le bon tileX/tileY ; vérifier qu'une **deuxième** tuile à un
  tileX/tileY différent produit des coordonnées dans **sa** bbox (preuve que le défaut n°1 est
  corrigé).
- **`GeoJsonMerger`** : 2 FeatureCollections → 1 ; `features.size()` = somme ; pas de perte.
- **`TileGridSource`** : 2 centres `#map` → la grille z13 x=5177 y=4532..4535 (4 tuiles,
  contiguës, sans trou).
- **`TrafficService`** : `TileFetcher` mocké ; cas nominal (toutes tuiles OK → `partial=false`) ;
  cas best-effort (1 tuile lève → features des autres présentes, `partial=true`).
- **`TrafficController`** (MockMvc, `TrafficService` mocké) : 200 + `application/geo+json` ;
  header `X-Predicta-Partial: true` quand partiel.

## Hors scope (plus tard)

- Cron 30 min de scraping + persistence en BDD (réutilisera les briques pures, mode tout-ou-rien).
- Élargissement de la grille (colonnes x supplémentaires) si la couverture lon doit dépasser
  47.505..47.549.
- Cache court / TTL sur `/traffic`.
