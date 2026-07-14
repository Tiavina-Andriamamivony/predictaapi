# /traffic Live Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /traffic` renvoie un seul FeatureCollection GeoJSON couvrant tout Tana, en fusionnant N tuiles MVT live depuis TAG-IP, sans persistence, best-effort sur échec de tuile.

**Architecture:** Briques pures sans I/O BDD : `TileGridSource` (config → liste de tuiles z13 contiguës), `TileFetcher` (tuile → bytes MVT décompressés, seul composant I/O), `MvtToGeoJsonConverter` (1 tuile + SON tileX/Y → FeatureCollection, corrige le bug du merge protobuf), `GeoJsonMerger` (concat features). `TrafficService` orchestre en best-effort, `TrafficController` sérialise. Le merge fiable se fait au niveau GeoJSON (coords absolues), jamais au niveau protobuf.

**Tech Stack:** Spring Boot 3.2, Java 21, `com.wdtinc:mapbox-vector-tile:3.1.0` (protobuf `VectorTile`), Jackson `ObjectMapper`, `RestTemplate`, JUnit 5 + Mockito + MockMvc (`spring-boot-starter-test`). Pas de Testcontainers (tout est pur).

**Package base:** `com.predicta.mg`. Tests miroir sous `src/test/java/com/predicta/mg/`.

---

## File Structure

- Create `src/main/java/com/predicta/mg/services/traffic/TileGridSource.java` — interface.
- Create `src/main/java/com/predicta/mg/services/traffic/TileGridSourceFromUrls.java` — impl config-driven.
- Create `src/main/java/com/predicta/mg/services/traffic/TileFetcher.java` — interface.
- Create `src/main/java/com/predicta/mg/services/traffic/TileFetcherHttp.java` — impl RestTemplate + gunzip.
- Create `src/main/java/com/predicta/mg/services/traffic/MvtToGeoJsonConverter.java` — pur, décode 1 tuile.
- Create `src/main/java/com/predicta/mg/services/traffic/GeoJsonMerger.java` — pur, concat.
- Create `src/main/java/com/predicta/mg/services/traffic/TrafficResult.java` — record `{ ObjectNode featureCollection, boolean partial }`.
- Create `src/main/java/com/predicta/mg/services/traffic/TrafficService.java` — orchestration best-effort.
- Modify `src/main/java/com/predicta/mg/endpoint/mvt/TrafficController.java` — recâbler sur `TrafficService`.
- Test: `src/test/java/com/predicta/mg/services/traffic/*Test.java` (un par composant pur) + `TrafficControllerTest.java`.

`TileCoordinate` réutilisé depuis `MvtScraperService.TileCoordinate` (record public `(int zoom, int tileX, int tileY)`). Les entités/repos `MvtTile`/`MergedMvtTile`/`GeoJsonResult` et les anciens services restent intacts (futur cron). Le chemin live ne les touche pas.

---

### Task 1: TileCoordinate partagé

On extrait le record `TileCoordinate` dans le package `traffic` pour que les briques pures ne dépendent pas de `MvtScraperService`.

**Files:**
- Create: `src/main/java/com/predicta/mg/services/traffic/TileCoordinate.java`

- [ ] **Step 1: Créer le record**

```java
package com.predicta.mg.services.traffic;

/** Coordonnée de tuile XYZ (slippy map). */
public record TileCoordinate(int zoom, int tileX, int tileY) {}
```

- [ ] **Step 2: Compiler**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/predicta/mg/services/traffic/TileCoordinate.java
git commit -m "feat(traffic): record TileCoordinate partagé"
```

---

### Task 2: GeoJsonMerger (pur)

Concatène les `features[]` de plusieurs FeatureCollections en un seul. Aucune dédup.

**Files:**
- Create: `src/main/java/com/predicta/mg/services/traffic/GeoJsonMerger.java`
- Test: `src/test/java/com/predicta/mg/services/traffic/GeoJsonMergerTest.java`

- [ ] **Step 1: Écrire le test qui échoue**

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeoJsonMergerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeoJsonMerger merger = new GeoJsonMerger(mapper);

    private ObjectNode fc(int nbFeatures) {
        ObjectNode fc = mapper.createObjectNode();
        fc.put("type", "FeatureCollection");
        var arr = fc.putArray("features");
        for (int i = 0; i < nbFeatures; i++) {
            ObjectNode f = mapper.createObjectNode();
            f.put("type", "Feature");
            f.put("idx", i);
            arr.add(f);
        }
        return fc;
    }

    @Test
    void merge_concatene_toutes_les_features_sans_perte() {
        ObjectNode merged = merger.merge(List.of(fc(2), fc(3)));

        assertThat(merged.get("type").asText()).isEqualTo("FeatureCollection");
        assertThat(merged.get("features")).hasSize(5);
    }

    @Test
    void merge_liste_vide_donne_collection_vide() {
        ObjectNode merged = merger.merge(List.of());

        assertThat(merged.get("type").asText()).isEqualTo("FeatureCollection");
        assertThat(merged.get("features")).isEmpty();
    }
}
```

- [ ] **Step 2: Lancer le test, vérifier l'échec**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.GeoJsonMergerTest`
Expected: FAIL — compilation error, `GeoJsonMerger` n'existe pas.

- [ ] **Step 3: Implémenter**

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Fusionne plusieurs FeatureCollections en concaténant leurs features. Aucune dédup. */
@Component
@RequiredArgsConstructor
public class GeoJsonMerger {

    private final ObjectMapper objectMapper;

    public ObjectNode merge(List<ObjectNode> collections) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("type", "FeatureCollection");
        ArrayNode features = out.putArray("features");
        for (ObjectNode fc : collections) {
            if (fc != null && fc.has("features")) {
                fc.withArray("features").forEach(features::add);
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Lancer le test, vérifier le succès**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.GeoJsonMergerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/predicta/mg/services/traffic/GeoJsonMerger.java src/test/java/com/predicta/mg/services/traffic/GeoJsonMergerTest.java
git commit -m "feat(traffic): GeoJsonMerger concat features"
```

---

### Task 3: TileGridSource (config → grille de tuiles contiguës)

Lit les centres `#map=zoom/xMerc/yMerc/rot`, décode EPSG:3857→WGS84→tuile, calcule la bbox englobante des centres, génère **toutes** les tuiles du rectangle (sans trou). Interface + impl.

**Files:**
- Create: `src/main/java/com/predicta/mg/services/traffic/TileGridSource.java`
- Create: `src/main/java/com/predicta/mg/services/traffic/TileGridSourceFromUrls.java`
- Test: `src/test/java/com/predicta/mg/services/traffic/TileGridSourceFromUrlsTest.java`

- [ ] **Step 1: Écrire l'interface**

```java
package com.predicta.mg.services.traffic;

import java.util.List;

/** Source de la liste de tuiles à fetcher pour couvrir la zone. */
public interface TileGridSource {
    List<TileCoordinate> tiles();
}
```

- [ ] **Step 2: Écrire le test qui échoue**

Les 2 URLs fournies décodent en z13 x=5177 y=4535 et y=4532. La grille englobante doit être x=5177, y=4532..4535 (4 tuiles contiguës, sans trou).

```java
package com.predicta.mg.services.traffic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TileGridSourceFromUrlsTest {

    private static final List<String> URLS = List.of(
            "https://traffic.tag-ip.com/index.html#map=13/5290085.13/-2148941.17/0",
            "https://traffic.tag-ip.com/index.html#map=13/5289339.87/-2134112.38/0"
    );

    @Test
    void genere_grille_contigue_englobant_les_deux_centres() {
        TileGridSourceFromUrls src = new TileGridSourceFromUrls(URLS);

        List<TileCoordinate> tiles = src.tiles();

        // x=5177, y de 4532 à 4535 inclus => 4 tuiles, sans trou
        assertThat(tiles).containsExactlyInAnyOrder(
                new TileCoordinate(13, 5177, 4532),
                new TileCoordinate(13, 5177, 4533),
                new TileCoordinate(13, 5177, 4534),
                new TileCoordinate(13, 5177, 4535)
        );
    }
}
```

- [ ] **Step 3: Lancer le test, vérifier l'échec**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.TileGridSourceFromUrlsTest`
Expected: FAIL — `TileGridSourceFromUrls` n'existe pas.

- [ ] **Step 4: Implémenter**

```java
package com.predicta.mg.services.traffic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Construit la grille de tuiles couvrant la zone à partir des centres de vue OpenLayers.
 * Chaque URL contient un fragment #map=zoom/xMercator/yMercator/rotation (EPSG:3857).
 * On décode chaque centre en tuile XYZ, puis on génère TOUTES les tuiles du rectangle
 * englobant (sans trou) au zoom commun.
 */
@Component
@Slf4j
public class TileGridSourceFromUrls implements TileGridSource {

    private static final double EARTH_RADIUS = 6378137.0;

    private final List<String> sourceUrls;

    public TileGridSourceFromUrls(@Value("${scrape.source-urls}") List<String> sourceUrls) {
        this.sourceUrls = sourceUrls;
    }

    @Override
    public List<TileCoordinate> tiles() {
        List<TileCoordinate> centers = sourceUrls.stream().map(this::centerTile).toList();

        int zoom = centers.get(0).zoom();
        int minX = centers.stream().mapToInt(TileCoordinate::tileX).min().orElseThrow();
        int maxX = centers.stream().mapToInt(TileCoordinate::tileX).max().orElseThrow();
        int minY = centers.stream().mapToInt(TileCoordinate::tileY).min().orElseThrow();
        int maxY = centers.stream().mapToInt(TileCoordinate::tileY).max().orElseThrow();

        List<TileCoordinate> grid = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                grid.add(new TileCoordinate(zoom, x, y));
            }
        }
        log.info("Grille tuiles z{} x[{}..{}] y[{}..{}] -> {} tuiles",
                zoom, minX, maxX, minY, maxY, grid.size());
        return grid;
    }

    private TileCoordinate centerTile(String url) {
        String[] parts = url.substring(url.indexOf("map=") + 4).split("/");
        int zoom = Integer.parseInt(parts[0]);
        double xMerc = Double.parseDouble(parts[1]);
        double yMerc = Double.parseDouble(parts[2]);

        double lon = Math.toDegrees(xMerc / EARTH_RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(yMerc / EARTH_RADIUS)) - Math.PI / 2);

        int n = 1 << zoom;
        int tileX = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(lat);
        int tileY = (int) Math.floor(
                (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);

        return new TileCoordinate(zoom, tileX, tileY);
    }
}
```

- [ ] **Step 5: Lancer le test, vérifier le succès**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.TileGridSourceFromUrlsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/predicta/mg/services/traffic/TileGridSource.java src/main/java/com/predicta/mg/services/traffic/TileGridSourceFromUrls.java src/test/java/com/predicta/mg/services/traffic/TileGridSourceFromUrlsTest.java
git commit -m "feat(traffic): TileGridSource grille contiguë depuis URLs"
```

---

### Task 4: MvtToGeoJsonConverter (pur) — cœur de la correction du merge

Décode UNE tuile MVT avec SON propre `tileX/tileY/zoom`, reprojette pixel→WGS84, renvoie un FeatureCollection. C'est ici qu'on corrige le bug : chaque tuile garde sa coordonnée, plus de `getFirst()` appliqué à tout.

La logique de décodage (zigzag, command stream, reprojection) est reprise de l'ancien `MvtToGeoJsonService` mais **paramétrée par `TileCoordinate`** et **sans aucune dépendance BDD / @Transactional**.

**Files:**
- Create: `src/main/java/com/predicta/mg/services/traffic/MvtToGeoJsonConverter.java`
- Test: `src/test/java/com/predicta/mg/services/traffic/MvtToGeoJsonConverterTest.java`

- [ ] **Step 1: Écrire le test qui échoue**

Le test encode à la main 2 tuiles MVT minimales (1 LineString chacune, même pixel local) à des `tileX` différents, et vérifie que les coordonnées WGS84 produites tombent dans la bbox de CHAQUE tuile respective. Si le bug existait (coord de la 1ère appliquée aux 2), la 2ème tuile aurait les coords de la 1ère.

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MvtToGeoJsonConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MvtToGeoJsonConverter converter = new MvtToGeoJsonConverter(mapper);

    /** Construit une tuile MVT avec un layer "speeds" et 1 LineString de 2 points. */
    private byte[] tileWithOneLine(int extent, int px0, int py0, int px1, int py1) {
        VectorTile.Tile.Feature feature = VectorTile.Tile.Feature.newBuilder()
                .setType(VectorTile.Tile.GeomType.LINESTRING)
                // MoveTo(1) count=1 ; dx,dy zigzag ; LineTo(2) count=1 ; dx,dy zigzag
                .addGeometry((1 << 3) | 1)          // MoveTo, count 1
                .addGeometry(zigzag(px0))
                .addGeometry(zigzag(py0))
                .addGeometry((1 << 3) | 2)          // LineTo, count 1
                .addGeometry(zigzag(px1 - px0))
                .addGeometry(zigzag(py1 - py0))
                .build();
        VectorTile.Tile.Layer layer = VectorTile.Tile.Layer.newBuilder()
                .setVersion(2)
                .setName("speeds")
                .setExtent(extent)
                .addFeatures(feature)
                .build();
        return VectorTile.Tile.newBuilder().addLayers(layer).build().toByteArray();
    }

    private int zigzag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    private double lonOf(ObjectNode fc) {
        return fc.withArray("features").get(0)
                .get("geometry").withArray("coordinates").get(0).get(0).asDouble();
    }

    @Test
    void deux_tuiles_a_des_tileX_differents_donnent_des_lon_differentes() {
        byte[] mvt = tileWithOneLine(4096, 100, 100, 200, 200);

        ObjectNode fcA = converter.convert(new TileCoordinate(13, 5177, 4535), mvt);
        ObjectNode fcB = converter.convert(new TileCoordinate(13, 5178, 4535), mvt);

        double lonA = lonOf(fcA);
        double lonB = lonOf(fcB);

        // tileX 5178 est à l'est de 5177 -> longitude plus grande
        assertThat(lonB).isGreaterThan(lonA);
        // chaque lon doit tomber dans la bbox de sa tuile (z13: largeur tuile ~0.0439°)
        assertThat(lonA).isBetween(47.5049, 47.5489);
        assertThat(lonB).isBetween(47.5489, 47.5929);
    }

    @Test
    void produit_un_featurecollection_avec_la_geometrie_linestring() {
        byte[] mvt = tileWithOneLine(4096, 0, 0, 100, 100);

        ObjectNode fc = converter.convert(new TileCoordinate(13, 5177, 4535), mvt);

        assertThat(fc.get("type").asText()).isEqualTo("FeatureCollection");
        assertThat(fc.withArray("features")).hasSize(1);
        var feat = fc.withArray("features").get(0);
        assertThat(feat.get("geometry").get("type").asText()).isEqualTo("LineString");
        assertThat(feat.get("properties").get("layer").asText()).isEqualTo("speeds");
    }
}
```

- [ ] **Step 2: Lancer le test, vérifier l'échec**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.MvtToGeoJsonConverterTest`
Expected: FAIL — `MvtToGeoJsonConverter` n'existe pas.

- [ ] **Step 3: Implémenter**

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Convertit UNE tuile MVT en FeatureCollection GeoJSON, reprojetée avec SA propre
 * coordonnée de tuile (tileX/tileY/zoom). Pur : aucune I/O, aucune persistence.
 * Le merge fiable se fait ensuite au niveau GeoJSON (coords absolues), jamais sur le protobuf.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MvtToGeoJsonConverter {

    private static final int DEFAULT_EXTENT = 4096;

    private final ObjectMapper objectMapper;

    public ObjectNode convert(TileCoordinate coord, byte[] mvtData) {
        try {
            VectorTile.Tile tile = VectorTile.Tile.parseFrom(mvtData);

            ObjectNode fc = objectMapper.createObjectNode();
            fc.put("type", "FeatureCollection");
            ArrayNode features = fc.putArray("features");

            for (VectorTile.Tile.Layer layer : tile.getLayersList()) {
                int extent = layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_EXTENT;
                for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
                    ObjectNode node = buildFeature(feature, layer, extent, coord);
                    if (node != null) features.add(node);
                }
            }
            return fc;
        } catch (Exception e) {
            throw new RuntimeException("Conversion MVT -> GeoJSON échouée pour tuile " + coord, e);
        }
    }

    private ObjectNode buildFeature(VectorTile.Tile.Feature feature, VectorTile.Tile.Layer layer,
                                    int extent, TileCoordinate c) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "Feature");
            ObjectNode props = node.putObject("properties");
            props.put("layer", layer.getName());
            decodeProperties(feature, layer, props);

            ObjectNode geometry = decodeGeometry(feature, extent, c);
            if (geometry == null) return null;
            node.set("geometry", geometry);
            return node;
        } catch (Exception e) {
            log.warn("Feature ignorée id={} layer={} : {}", feature.getId(), layer.getName(), e.getMessage());
            return null;
        }
    }

    private void decodeProperties(VectorTile.Tile.Feature feature,
                                  VectorTile.Tile.Layer layer, ObjectNode props) {
        List<Integer> tags = feature.getTagsList();
        for (int i = 0; i + 1 < tags.size(); i += 2) {
            String key = layer.getKeys(tags.get(i));
            VectorTile.Tile.Value v = layer.getValues(tags.get(i + 1));
            if (v.hasBoolValue())        props.put(key, v.getBoolValue());
            else if (v.hasDoubleValue()) props.put(key, v.getDoubleValue());
            else if (v.hasFloatValue())  props.put(key, (double) v.getFloatValue());
            else if (v.hasIntValue())    props.put(key, v.getIntValue());
            else if (v.hasSintValue())   props.put(key, v.getSintValue());
            else if (v.hasUintValue())   props.put(key, v.getUintValue());
            else if (v.hasStringValue()) props.put(key, v.getStringValue());
        }
    }

    private ObjectNode decodeGeometry(VectorTile.Tile.Feature feature, int extent, TileCoordinate c) {
        return switch (feature.getType()) {
            case POINT      -> decodePoint(feature.getGeometryList(), extent, c);
            case LINESTRING -> decodeLineString(feature.getGeometryList(), extent, c);
            case POLYGON    -> decodePolygon(feature.getGeometryList(), extent, c);
            default         -> null;
        };
    }

    private ObjectNode decodePoint(List<Integer> cmds, int extent, TileCoordinate c) {
        List<double[]> pts = new ArrayList<>();
        int i = 0, cx = 0, cy = 0;
        while (i < cmds.size()) {
            int cmd = cmds.get(i) & 0x7, count = cmds.get(i) >> 3; i++;
            if (cmd == 1) {
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++));
                    cy += zigzag(cmds.get(i++));
                    pts.add(toWgs84(cx, cy, extent, c));
                }
            } else i += 2 * count;
        }
        if (pts.isEmpty()) return null;
        ObjectNode n = objectMapper.createObjectNode();
        if (pts.size() == 1) {
            n.put("type", "Point");
            n.set("coordinates", coord(pts.get(0)));
        } else {
            n.put("type", "MultiPoint");
            ArrayNode a = n.putArray("coordinates");
            pts.forEach(p -> a.add(coord(p)));
        }
        return n;
    }

    private ObjectNode decodeLineString(List<Integer> cmds, int extent, TileCoordinate c) {
        List<List<double[]>> lines = new ArrayList<>();
        List<double[]> cur = null;
        int i = 0, cx = 0, cy = 0;
        while (i < cmds.size()) {
            int cmd = cmds.get(i) & 0x7, count = cmds.get(i) >> 3; i++;
            if (cmd == 1) {
                cur = new ArrayList<>(); lines.add(cur);
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, c));
                }
            } else if (cmd == 2 && cur != null) {
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, c));
                }
            } else i += 2 * count;
        }
        if (lines.isEmpty()) return null;
        ObjectNode n = objectMapper.createObjectNode();
        if (lines.size() == 1) {
            n.put("type", "LineString");
            n.set("coordinates", coords(lines.get(0)));
        } else {
            n.put("type", "MultiLineString");
            ArrayNode a = n.putArray("coordinates");
            lines.forEach(l -> a.add(coords(l)));
        }
        return n;
    }

    private ObjectNode decodePolygon(List<Integer> cmds, int extent, TileCoordinate c) {
        List<List<double[]>> rings = new ArrayList<>();
        List<double[]> cur = null;
        int i = 0, cx = 0, cy = 0;
        while (i < cmds.size()) {
            int cmd = cmds.get(i) & 0x7, count = cmds.get(i) >> 3; i++;
            if (cmd == 1) {
                cur = new ArrayList<>(); rings.add(cur);
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, c));
                }
            } else if (cmd == 2 && cur != null) {
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, c));
                }
            } else if (cmd == 7 && cur != null && !cur.isEmpty()) {
                cur.add(cur.get(0));
            }
        }
        if (rings.isEmpty()) return null;
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "Polygon");
        ArrayNode a = n.putArray("coordinates");
        rings.forEach(r -> a.add(coords(r)));
        return n;
    }

    private int zigzag(int n) {
        return (n >> 1) ^ -(n & 1);
    }

    private double[] toWgs84(int px, int py, int extent, TileCoordinate c) {
        int n = 1 << c.zoom();
        double lon = (c.tileX() + (double) px / extent) / n * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(
                Math.PI * (1 - 2.0 * (c.tileY() + (double) py / extent) / n))));
        return new double[]{ Math.round(lon * 1e7) / 1e7, Math.round(lat * 1e7) / 1e7 };
    }

    private ArrayNode coord(double[] p) {
        ArrayNode a = objectMapper.createArrayNode();
        a.add(p[0]); a.add(p[1]);
        return a;
    }

    private ArrayNode coords(List<double[]> pts) {
        ArrayNode a = objectMapper.createArrayNode();
        pts.forEach(p -> a.add(coord(p)));
        return a;
    }
}
```

- [ ] **Step 4: Lancer le test, vérifier le succès**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.MvtToGeoJsonConverterTest`
Expected: PASS (2 tests). La preuve clé : `lonB > lonA`, chaque lon dans sa propre bbox.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/predicta/mg/services/traffic/MvtToGeoJsonConverter.java src/test/java/com/predicta/mg/services/traffic/MvtToGeoJsonConverterTest.java
git commit -m "feat(traffic): MvtToGeoJsonConverter par-tuile (fix merge protobuf)"
```

---

### Task 5: TileFetcher (interface + impl HTTP)

`byte[] fetch(TileCoordinate)` : applique le template d'URL, GET via `RestTemplate`, gunzip si magic bytes `1F 8B`. Seul composant à faire de l'I/O. Lève une exception en cas de réponse vide/erreur (best-effort géré en amont par `TrafficService`).

**Files:**
- Create: `src/main/java/com/predicta/mg/services/traffic/TileFetcher.java`
- Create: `src/main/java/com/predicta/mg/services/traffic/TileFetcherHttp.java`
- Test: `src/test/java/com/predicta/mg/services/traffic/TileFetcherHttpTest.java`

- [ ] **Step 1: Écrire l'interface**

```java
package com.predicta.mg.services.traffic;

/** Récupère les octets MVT (décompressés) d'une tuile. Lève une exception si échec. */
public interface TileFetcher {
    byte[] fetch(TileCoordinate coord);
}
```

- [ ] **Step 2: Écrire le test qui échoue (gunzip + template)**

On teste l'impl en mockant `RestTemplate`. Vérifie : URL templatée correctement, et décompression gzip quand magic bytes présents.

```java
package com.predicta.mg.services.traffic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TileFetcherHttpTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final String template = "https://traffic.tag-ip.com/tile/v1/car/tile({x},{y},{zoom}).mvt";
    private final TileFetcherHttp fetcher = new TileFetcherHttp(restTemplate, template);

    private byte[] gzip(byte[] data) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(bos)) { gz.write(data); }
        return bos.toByteArray();
    }

    @Test
    void fetch_template_url_et_decompresse_gzip() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        byte[] gzipped = gzip(payload);
        String expectedUrl = "https://traffic.tag-ip.com/tile/v1/car/tile(5177,4535,13).mvt";
        when(restTemplate.exchange(eq(expectedUrl), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(gzipped));

        byte[] out = fetcher.fetch(new TileCoordinate(13, 5177, 4535));

        assertThat(out).containsExactly(1, 2, 3, 4);
    }

    @Test
    void fetch_reponse_vide_leve_exception() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[0]));

        try {
            fetcher.fetch(new TileCoordinate(13, 5177, 4535));
            assertThat(false).as("aurait dû lever").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("vide");
        }
    }
}
```

- [ ] **Step 3: Lancer le test, vérifier l'échec**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.TileFetcherHttpTest`
Expected: FAIL — `TileFetcherHttp` n'existe pas.

- [ ] **Step 4: Implémenter**

```java
package com.predicta.mg.services.traffic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/** Fetch HTTP d'une tuile .pbf via RestTemplate, gunzip si nécessaire. */
@Component
@Slf4j
public class TileFetcherHttp implements TileFetcher {

    private final RestTemplate restTemplate;
    private final String tileTemplate;

    public TileFetcherHttp(RestTemplate restTemplate,
                           @Value("${scrape.tile-template}") String tileTemplate) {
        this.restTemplate = restTemplate;
        this.tileTemplate = tileTemplate;
    }

    @Override
    public byte[] fetch(TileCoordinate coord) {
        String url = tileTemplate
                .replace("{zoom}", String.valueOf(coord.zoom()))
                .replace("{x}", String.valueOf(coord.tileX()))
                .replace("{y}", String.valueOf(coord.tileY()));
        log.info("Fetching MVT -> {}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/x-protobuf");
        headers.set("User-Agent", "Mozilla/5.0");

        ResponseEntity<byte[]> response =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        byte[] data = response.getBody();
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Réponse MVT vide pour : " + url);
        }
        try {
            return decompress(data);
        } catch (IOException e) {
            throw new IllegalStateException("Décompression MVT échouée pour : " + url, e);
        }
    }

    private byte[] decompress(byte[] data) throws IOException {
        if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data));
                 var out = new ByteArrayOutputStream()) {
                gzip.transferTo(out);
                return out.toByteArray();
            }
        }
        return data;
    }
}
```

- [ ] **Step 5: Lancer le test, vérifier le succès**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.TileFetcherHttpTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/predicta/mg/services/traffic/TileFetcher.java src/main/java/com/predicta/mg/services/traffic/TileFetcherHttp.java src/test/java/com/predicta/mg/services/traffic/TileFetcherHttpTest.java
git commit -m "feat(traffic): TileFetcher HTTP + gunzip"
```

---

### Task 6: TrafficResult + TrafficService (orchestration best-effort)

`TrafficService` enchaîne grid→fetch→convert→merge. Best-effort : try/catch par tuile, collecte les succès, marque `partial=true` si au moins une tuile a échoué. Renvoie `TrafficResult`.

**Files:**
- Create: `src/main/java/com/predicta/mg/services/traffic/TrafficResult.java`
- Create: `src/main/java/com/predicta/mg/services/traffic/TrafficService.java`
- Test: `src/test/java/com/predicta/mg/services/traffic/TrafficServiceTest.java`

- [ ] **Step 1: Écrire le record TrafficResult**

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Résultat live : la FeatureCollection mergée + indicateur de complétude. */
public record TrafficResult(ObjectNode featureCollection, boolean partial) {}
```

- [ ] **Step 2: Écrire le test qui échoue**

Mocke `TileGridSource`, `TileFetcher`, utilise les vrais `MvtToGeoJsonConverter` et `GeoJsonMerger`. Cas nominal (2 tuiles OK → partial false, somme des features) et best-effort (1 tuile lève → partial true, features de l'autre présentes).

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrafficServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MvtToGeoJsonConverter converter = new MvtToGeoJsonConverter(mapper);
    private final GeoJsonMerger merger = new GeoJsonMerger(mapper);
    private final TileGridSource grid = mock(TileGridSource.class);
    private final TileFetcher fetcher = mock(TileFetcher.class);

    private final TrafficService service = new TrafficService(grid, fetcher, converter, merger, mapper);

    private byte[] oneLine() {
        VectorTile.Tile.Feature f = VectorTile.Tile.Feature.newBuilder()
                .setType(VectorTile.Tile.GeomType.LINESTRING)
                .addGeometry((1 << 3) | 1).addGeometry(zz(10)).addGeometry(zz(10))
                .addGeometry((1 << 3) | 2).addGeometry(zz(20)).addGeometry(zz(20))
                .build();
        VectorTile.Tile.Layer l = VectorTile.Tile.Layer.newBuilder()
                .setVersion(2).setName("speeds").setExtent(4096).addFeatures(f).build();
        return VectorTile.Tile.newBuilder().addLayers(l).build().toByteArray();
    }

    private int zz(int n) { return (n << 1) ^ (n >> 31); }

    @Test
    void nominal_toutes_tuiles_ok_partial_false() {
        TileCoordinate t1 = new TileCoordinate(13, 5177, 4535);
        TileCoordinate t2 = new TileCoordinate(13, 5177, 4534);
        when(grid.tiles()).thenReturn(List.of(t1, t2));
        when(fetcher.fetch(eq(t1))).thenReturn(oneLine());
        when(fetcher.fetch(eq(t2))).thenReturn(oneLine());

        TrafficResult result = service.liveGeoJson();

        assertThat(result.partial()).isFalse();
        assertThat(result.featureCollection().withArray("features")).hasSize(2);
    }

    @Test
    void best_effort_une_tuile_echoue_partial_true() {
        TileCoordinate t1 = new TileCoordinate(13, 5177, 4535);
        TileCoordinate t2 = new TileCoordinate(13, 5177, 4534);
        when(grid.tiles()).thenReturn(List.of(t1, t2));
        when(fetcher.fetch(eq(t1))).thenReturn(oneLine());
        when(fetcher.fetch(eq(t2))).thenThrow(new IllegalStateException("timeout"));

        TrafficResult result = service.liveGeoJson();

        assertThat(result.partial()).isTrue();
        assertThat(result.featureCollection().withArray("features")).hasSize(1);
    }
}
```

- [ ] **Step 3: Lancer le test, vérifier l'échec**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.TrafficServiceTest`
Expected: FAIL — `TrafficService` n'existe pas.

- [ ] **Step 4: Implémenter**

```java
package com.predicta.mg.services.traffic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestration live de /traffic : grille -> fetch -> convert (par tuile) -> merge GeoJSON.
 * Best-effort : une tuile en échec est ignorée (log warn) et marque le résultat partiel.
 * Aucune persistence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrafficService {

    private final TileGridSource tileGridSource;
    private final TileFetcher tileFetcher;
    private final MvtToGeoJsonConverter converter;
    private final GeoJsonMerger merger;
    private final ObjectMapper objectMapper;

    public TrafficResult liveGeoJson() {
        List<TileCoordinate> tiles = tileGridSource.tiles();
        List<ObjectNode> collections = new ArrayList<>();
        boolean partial = false;

        for (TileCoordinate coord : tiles) {
            try {
                byte[] mvt = tileFetcher.fetch(coord);
                collections.add(converter.convert(coord, mvt));
            } catch (Exception e) {
                partial = true;
                log.warn("Tuile ignorée (best-effort) {} : {}", coord, e.getMessage());
            }
        }

        ObjectNode merged = merger.merge(collections);
        log.info("/traffic live : {} tuiles, {} features, partial={}",
                tiles.size(), merged.withArray("features").size(), partial);
        return new TrafficResult(merged, partial);
    }
}
```

Note : `objectMapper` est injecté pour cohérence/future sérialisation interne ; il n'est pas utilisé dans cette version. Si l'engineer préfère ne pas l'injecter inutilement (YAGNI), il peut le retirer du constructeur ET du test. Décision : **le retirer** — non utilisé. Retire le champ `objectMapper`, le paramètre du constructeur, et l'argument `mapper` dans le `new TrafficService(...)` du test (Step 2).

- [ ] **Step 5: Lancer le test, vérifier le succès**

Run: `./gradlew test --tests com.predicta.mg.services.traffic.TrafficServiceTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/predicta/mg/services/traffic/TrafficResult.java src/main/java/com/predicta/mg/services/traffic/TrafficService.java src/test/java/com/predicta/mg/services/traffic/TrafficServiceTest.java
git commit -m "feat(traffic): TrafficService orchestration best-effort"
```

---

### Task 7: TrafficController recâblé sur TrafficService

`GET /traffic` appelle `TrafficService.liveGeoJson()`, sérialise la FeatureCollection, pose `Content-Type: application/geo+json` et `X-Predicta-Partial: true` si partiel.

**Files:**
- Modify: `src/main/java/com/predicta/mg/endpoint/mvt/TrafficController.java` (remplacer entièrement)
- Test: `src/test/java/com/predicta/mg/endpoint/mvt/TrafficControllerTest.java`

- [ ] **Step 1: Écrire le test qui échoue (MockMvc standalone, TrafficService mocké)**

```java
package com.predicta.mg.endpoint.mvt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.predicta.mg.services.traffic.TrafficResult;
import com.predicta.mg.services.traffic.TrafficService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrafficControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TrafficService service = mock(TrafficService.class);
    private MockMvc mockMvc;

    private ObjectNode emptyFc() {
        ObjectNode fc = mapper.createObjectNode();
        fc.put("type", "FeatureCollection");
        fc.putArray("features");
        return fc;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TrafficController(service, mapper)).build();
    }

    @Test
    void traffic_complet_renvoie_200_geojson_sans_header_partial() throws Exception {
        when(service.liveGeoJson()).thenReturn(new TrafficResult(emptyFc(), false));

        mockMvc.perform(get("/traffic"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/geo+json"))
                .andExpect(header().doesNotExist("X-Predicta-Partial"))
                .andExpect(jsonPath("$.type").value("FeatureCollection"));
    }

    @Test
    void traffic_partiel_pose_header_partial() throws Exception {
        when(service.liveGeoJson()).thenReturn(new TrafficResult(emptyFc(), true));

        mockMvc.perform(get("/traffic"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Predicta-Partial", "true"));
    }
}
```

- [ ] **Step 2: Lancer le test, vérifier l'échec**

Run: `./gradlew test --tests com.predicta.mg.endpoint.mvt.TrafficControllerTest`
Expected: FAIL — constructeur `TrafficController(TrafficService, ObjectMapper)` n'existe pas (ancien constructeur prend `MvtScraperService`/`MvtToGeoJsonService`).

- [ ] **Step 3: Réécrire le controller**

```java
package com.predicta.mg.endpoint.mvt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.predicta.mg.services.traffic.TrafficResult;
import com.predicta.mg.services.traffic.TrafficService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TrafficController {

    private final TrafficService trafficService;
    private final ObjectMapper objectMapper;

    /**
     * Traffic live de tout Tana à l'instant t : fetch + convert + merge des tuiles MVT,
     * renvoyé en un seul FeatureCollection GeoJSON. Aucune persistence.
     * GET /traffic
     */
    @GetMapping("/traffic")
    public ResponseEntity<String> traffic() throws JsonProcessingException {
        log.info("GET /traffic — fetch + merge live");
        TrafficResult result = trafficService.liveGeoJson();
        String body = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result.featureCollection());

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header("Content-Type", "application/geo+json");
        if (result.partial()) {
            builder.header("X-Predicta-Partial", "true");
        }
        return builder.body(body);
    }
}
```

- [ ] **Step 4: Lancer le test, vérifier le succès**

Run: `./gradlew test --tests com.predicta.mg.endpoint.mvt.TrafficControllerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/predicta/mg/endpoint/mvt/TrafficController.java src/test/java/com/predicta/mg/endpoint/mvt/TrafficControllerTest.java
git commit -m "feat(traffic): controller live branché sur TrafficService"
```

---

### Task 8: Build complet + format + commit final

Vérifier que tout compile, tous les tests passent, et appliquer le format google-java-format.

**Files:** aucun nouveau.

- [ ] **Step 1: Format**

Run: `./format.sh`
Expected: reformate les fichiers `traffic/` sans erreur.

- [ ] **Step 2: Build complet**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Tous les tests `traffic` + `TrafficControllerTest` verts. Note : `FacadeIT` / autres tests Testcontainers nécessitent Docker ; si Docker absent, lancer plutôt `./gradlew test --tests "com.predicta.mg.services.traffic.*" --tests "com.predicta.mg.endpoint.mvt.TrafficControllerTest"` et le noter.

- [ ] **Step 3: Commit si le format a modifié des fichiers**

```bash
git add -A
git commit -m "style(traffic): google-java-format" || echo "rien à formater"
```

---

## Self-Review

**Spec coverage :**
- Merge fiable (convert par-tuile + concat GeoJSON) → Tasks 4 + 2. ✔
- Bug protobuf corrigé (preuve `lonB>lonA`) → Task 4 test. ✔
- Couverture tout Tana (grille z13 contiguë x=5177 y=4532..4535) → Task 3. ✔
- Live, zéro persist → Tasks 6+7 (aucun repo injecté). ✔
- Best-effort + `X-Predicta-Partial` → Tasks 6 (partial) + 7 (header). ✔
- Pas de dédup → Task 2 (concat brut). ✔
- Clean code, pur séparé de l'I/O → composants Tasks 2-6, seul `TileFetcherHttp` fait l'I/O. ✔
- Entités/anciens services intacts → non touchés par le plan. ✔
- RestTemplate bean déjà présent → utilisé tel quel en Task 5. ✔

**Placeholder scan :** aucun TBD/TODO ; tout le code est complet. ✔

**Type consistency :** `TileCoordinate(zoom,tileX,tileY)` cohérent partout ; `TrafficResult(featureCollection, partial)` utilisé identique en 6 et 7 ; `liveGeoJson()` nom identique 6/7 ; `convert(TileCoordinate, byte[])`, `merge(List<ObjectNode>)`, `fetch(TileCoordinate)`, `tiles()` cohérents. La note YAGNI en Task 6 supprime `objectMapper` du `TrafficService` (champ+ctor+arg test) — appliquer pour éviter l'incohérence. ✔
