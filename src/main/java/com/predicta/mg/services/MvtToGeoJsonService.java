package com.predicta.mg.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.predicta.mg.models.GeoJsonResult;
import com.predicta.mg.models.MergedMvtTile;
import com.predicta.mg.models.MvtTile;
import com.predicta.mg.repository.GeoJsonResultRepository;
import com.predicta.mg.repository.MergedMvtTileRepository;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MvtToGeoJsonService {

    private final MergedMvtTileRepository mergedMvtTileRepository;
    private final GeoJsonResultRepository geoJsonResultRepository;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_EXTENT = 4096;

    @Transactional
    public GeoJsonResult convertLatest() {
        MergedMvtTile merged = mergedMvtTileRepository
                .findTopByOrderByMergedAtDesc()
                .orElseThrow(() -> new IllegalStateException("Aucune tile mergée disponible"));

        // Déjà converti ? on retourne l'existant
        return geoJsonResultRepository
                .findByMergedTile(merged)
                .orElseGet(() -> convert(merged));
    }

    @Transactional
    public GeoJsonResult convert(MergedMvtTile mergedTile) {
        try {
            VectorTile.Tile tile = VectorTile.Tile.parseFrom(mergedTile.getMergedData());

            MvtTile ref  = mergedTile.getSourceTiles().get(0);
            int zoom     = ref.getZoom();
            int tileX    = ref.getTileX();
            int tileY    = ref.getTileY();

            ObjectNode featureCollection = objectMapper.createObjectNode();
            featureCollection.put("type", "FeatureCollection");
            ArrayNode features = featureCollection.putArray("features");

            for (VectorTile.Tile.Layer layer : tile.getLayersList()) {
                int extent = layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_EXTENT;
                for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
                    ObjectNode featureNode = buildFeature(feature, layer, extent, zoom, tileX, tileY);
                    if (featureNode != null) features.add(featureNode);
                }
            }

            String geoJson = objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(featureCollection);

            log.info("Conversion terminée : {} features", features.size());

            GeoJsonResult result = GeoJsonResult.builder()
                    .mergedTile(mergedTile)
                    .geoJsonContent(geoJson)
                    .convertedAt(LocalDateTime.now())
                    .featureCount(features.size())
                    .build();

            return geoJsonResultRepository.save(result);

        } catch (Exception e) {
            throw new RuntimeException("Conversion MVT → GeoJSON échouée", e);
        }
    }

    // -------------------------------------------------------------------------
    // Construction d'une Feature GeoJSON
    // -------------------------------------------------------------------------

    private ObjectNode buildFeature(VectorTile.Tile.Feature feature, VectorTile.Tile.Layer layer,
                                    int extent, int zoom, int tileX, int tileY) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "Feature");

            ObjectNode props = node.putObject("properties");
            props.put("layer", layer.getName());
            decodeProperties(feature, layer, props);

            ObjectNode geometry = decodeGeometry(feature, extent, zoom, tileX, tileY);
            if (geometry == null) return null;

            node.set("geometry", geometry);
            return node;

        } catch (Exception e) {
            log.warn("Feature ignorée id={} layer={} : {}", feature.getId(), layer.getName(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Décodage des propriétés
    // -------------------------------------------------------------------------

    private void decodeProperties(VectorTile.Tile.Feature feature,
                                  VectorTile.Tile.Layer layer, ObjectNode props) {
        List<Integer> tags = feature.getTagsList();
        for (int i = 0; i + 1 < tags.size(); i += 2) {
            String key               = layer.getKeys(tags.get(i));
            VectorTile.Tile.Value v  = layer.getValues(tags.get(i + 1));

            if      (v.hasBoolValue())   props.put(key, v.getBoolValue());
            else if (v.hasDoubleValue()) props.put(key, v.getDoubleValue());
            else if (v.hasFloatValue())  props.put(key, (double) v.getFloatValue());
            else if (v.hasIntValue())    props.put(key, v.getIntValue());
            else if (v.hasSintValue())   props.put(key, v.getSintValue());
            else if (v.hasUintValue())   props.put(key, v.getUintValue());
            else if (v.hasStringValue()) props.put(key, v.getStringValue());
        }
    }

    // -------------------------------------------------------------------------
    // Décodage des géométries
    // -------------------------------------------------------------------------

    private ObjectNode decodeGeometry(VectorTile.Tile.Feature feature,
                                      int extent, int zoom, int tileX, int tileY) {
        return switch (feature.getType()) {
            case POINT      -> decodePoint(feature.getGeometryList(), extent, zoom, tileX, tileY);
            case LINESTRING -> decodeLineString(feature.getGeometryList(), extent, zoom, tileX, tileY);
            case POLYGON    -> decodePolygon(feature.getGeometryList(), extent, zoom, tileX, tileY);
            default         -> null;
        };
    }

    private ObjectNode decodePoint(List<Integer> cmds, int extent, int zoom, int tileX, int tileY) {
        List<double[]> pts = new ArrayList<>();
        int i = 0, cx = 0, cy = 0;
        while (i < cmds.size()) {
            int cmd = cmds.get(i) & 0x7, count = cmds.get(i) >> 3; i++;
            if (cmd == 1) {
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++));
                    cy += zigzag(cmds.get(i++));
                    pts.add(toWgs84(cx, cy, extent, zoom, tileX, tileY));
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

    private ObjectNode decodeLineString(List<Integer> cmds, int extent, int zoom, int tileX, int tileY) {
        List<List<double[]>> lines = new ArrayList<>();
        List<double[]> cur = null;
        int i = 0, cx = 0, cy = 0;
        while (i < cmds.size()) {
            int cmd = cmds.get(i) & 0x7, count = cmds.get(i) >> 3; i++;
            if (cmd == 1) {
                cur = new ArrayList<>(); lines.add(cur);
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, zoom, tileX, tileY));
                }
            } else if (cmd == 2 && cur != null) {
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, zoom, tileX, tileY));
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

    private ObjectNode decodePolygon(List<Integer> cmds, int extent, int zoom, int tileX, int tileY) {
        List<List<double[]>> rings = new ArrayList<>();
        List<double[]> cur = null;
        int i = 0, cx = 0, cy = 0;
        while (i < cmds.size()) {
            int cmd = cmds.get(i) & 0x7, count = cmds.get(i) >> 3; i++;
            if (cmd == 1) {
                cur = new ArrayList<>(); rings.add(cur);
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, zoom, tileX, tileY));
                }
            } else if (cmd == 2 && cur != null) {
                for (int j = 0; j < count; j++) {
                    cx += zigzag(cmds.get(i++)); cy += zigzag(cmds.get(i++));
                    cur.add(toWgs84(cx, cy, extent, zoom, tileX, tileY));
                }
            } else if (cmd == 7 && cur != null && !cur.isEmpty()) {
                cur.add(cur.get(0)); // fermer le ring
            }
        }
        if (rings.isEmpty()) return null;
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "Polygon");
        ArrayNode a = n.putArray("coordinates");
        rings.forEach(r -> a.add(coords(r)));
        return n;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    public Optional<String> getLatestGeoJson() {
        return geoJsonResultRepository
                .findTopByOrderByConvertedAtDesc()
                .map(GeoJsonResult::getGeoJsonContent);
    }


    /** Zigzag decode — encodage delta protobuf MVT */
    private int zigzag(int n) {
        return (n >> 1) ^ -(n & 1);
    }

    /** Pixel de tuile → [longitude, latitude] WGS84 */
    private double[] toWgs84(int px, int py, int extent, int zoom, int tileX, int tileY) {
        int n      = 1 << zoom;
        double lon = (tileX + (double) px / extent) / n * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(
                Math.PI * (1 - 2.0 * (tileY + (double) py / extent) / n))));
        return new double[]{
                Math.round(lon * 1e7) / 1e7,
                Math.round(lat * 1e7) / 1e7
        };
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