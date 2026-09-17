package com.predicta.mg.services.traffic;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeature;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.predicta.mg.services.traffic.geojson.SpeedFeatureProperties;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Convertit UNE tuile MVT en {@link GeoJsonFeatureCollection} typée, reprojetée avec SA propre
 * coordonnée de tuile. Pur : aucune I/O, aucune persistence. Le merge des tuiles se fait ensuite au
 * niveau GeoJSON (coordonnées absolues), jamais sur le protobuf.
 *
 * <p>Seul le layer "speeds" est conservé (parité source : {@code MVT({layers:"speeds"})}). Ce layer
 * ne contient que des lignes ; les autres types de géométrie sont donc volontairement ignorés.
 */
@Component
@Slf4j
public class MvtToGeoJsonConverter {

  private static final String SPEEDS_LAYER = "speeds";

  private static final String TAG_NAME = "name";
  private static final String TAG_SPEED = "speed";
  private static final String TAG_RATE = "rate";

  private static final int DEFAULT_EXTENT = 4096;

  private static final int COMMAND_MOVE_TO = 1;
  private static final int COMMAND_LINE_TO = 2;

  /**
   * Les 3 bits de poids faible d'une commande MVT portent son id, le reste son nombre de points.
   */
  private static final int COMMAND_ID_MASK = 0x7;

  private static final int COMMAND_REPEAT_SHIFT = 3;

  /** 2 délta encodés (x puis y) par point d'une géométrie. */
  private static final int DELTAS_PER_POINT = 2;

  public GeoJsonFeatureCollection convert(TileCoordinate tile, byte[] mvtData) {
    try {
      VectorTile.Tile decodedTile = VectorTile.Tile.parseFrom(mvtData);

      List<GeoJsonFeature> features = new ArrayList<>();
      for (VectorTile.Tile.Layer layer : decodedTile.getLayersList()) {
        if (!SPEEDS_LAYER.equals(layer.getName())) {
          continue;
        }
        int extent = layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_EXTENT;
        for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {
          GeoJsonFeature converted = buildFeature(feature, layer, extent, tile);
          if (converted != null) {
            features.add(converted);
          }
        }
      }
      GeoJsonFeatureCollection result = new GeoJsonFeatureCollection(features);
      log.info("Conversion MVT {} : {} features", tile, features.size());
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Conversion MVT -> GeoJSON échouée pour tuile " + tile, e);
    }
  }

  private GeoJsonFeature buildFeature(
      VectorTile.Tile.Feature feature,
      VectorTile.Tile.Layer layer,
      int extent,
      TileCoordinate tile) {
    try {
      GeoJsonGeometry geometry = decodeLineGeometry(feature, extent, tile);
      if (geometry == null) {
        return null;
      }
      return new GeoJsonFeature(decodeProperties(feature, layer), geometry);
    } catch (Exception e) {
      log.warn(
          "Feature ignorée id={} layer={} : {}", feature.getId(), layer.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * Lit les 3 tags utiles ({@code name}, {@code speed}, {@code rate}) en une passe sur la liste
   * plate de tags MVT (index de clé, index de valeur), <b>sans map intermédiaire</b>. Une HashMap —
   * plus ses clés et ses valeurs boxées — par feature, sur des centaines de milliers de features,
   * c'est de la mémoire et du GC pour trois valeurs. Comme la map avant elle, la dernière
   * occurrence d'une clé gagne.
   *
   * <p>{@code quartierId} reste null ici : posé en aval par l'enrichissement OSM (best-effort).
   */
  private SpeedFeatureProperties decodeProperties(
      VectorTile.Tile.Feature feature, VectorTile.Tile.Layer layer) {
    String name = null;
    Integer speed = null;
    Double rate = null;
    List<Integer> tags = feature.getTagsList();
    for (int i = 0; i + 1 < tags.size(); i += 2) {
      String key = layer.getKeys(tags.get(i));
      Object value = decodeValue(layer.getValues(tags.get(i + 1)));
      // Chaîne de if plutôt qu'un switch : en Java, un corps de branche « -> » est une expression,
      // et l'affectation y devient une affectation au sein d'une expression (Règle 1).
      if (TAG_NAME.equals(key)) {
        name = asString(value);
      } else if (TAG_SPEED.equals(key)) {
        speed = asInt(value);
      } else if (TAG_RATE.equals(key)) {
        rate = asDouble(value);
      }
      // tout autre tag publié par la source est ignoré
    }
    return new SpeedFeatureProperties(name, null, speed, rate);
  }

  private Object decodeValue(VectorTile.Tile.Value value) {
    if (value.hasBoolValue()) return value.getBoolValue();
    if (value.hasDoubleValue()) return value.getDoubleValue();
    if (value.hasFloatValue()) return (double) value.getFloatValue();
    if (value.hasIntValue()) return value.getIntValue();
    if (value.hasSintValue()) return value.getSintValue();
    if (value.hasUintValue()) return value.getUintValue();
    if (value.hasStringValue()) return value.getStringValue();
    return null;
  }

  private String asString(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  private Integer asInt(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private Double asDouble(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  /**
   * Décode le flux de commandes MVT (zigzag + delta) d'une géométrie ligne. Chaque commande encode
   * un id (3 bits de poids faible) et un nombre de répétitions : cmd 1 = MoveTo (début de ligne),
   * cmd 2 = LineTo (points suivants). Les coordonnées sont relatives au point précédent (delta),
   * d'où le curseur cumulatif. Une seule ligne -> LineString, plusieurs -> MultiLineString.
   */
  private GeoJsonGeometry decodeLineGeometry(
      VectorTile.Tile.Feature feature, int extent, TileCoordinate tile) {
    if (feature.getType() != VectorTile.Tile.GeomType.LINESTRING) {
      return null;
    }
    List<Integer> commands = feature.getGeometryList();
    List<List<double[]>> lines = new ArrayList<>();
    List<double[]> currentLine = null;
    // Le curseur est cumulatif : les coordonnées MVT sont des délta par rapport au point précédent.
    int index = 0;
    int cursorX = 0;
    int cursorY = 0;
    while (index < commands.size()) {
      int commandId = commands.get(index) & COMMAND_ID_MASK;
      int repeatCount = commands.get(index) >> COMMAND_REPEAT_SHIFT;
      index++;
      boolean startsNewLine = commandId == COMMAND_MOVE_TO;
      // Un LineTo sans MoveTo préalable n'a pas de ligne à étendre : il est ignoré comme les
      // commandes non gérées (ClosePath...).
      boolean extendsLine = commandId == COMMAND_LINE_TO && currentLine != null;
      if (!startsNewLine && !extendsLine) {
        index += DELTAS_PER_POINT * repeatCount;
        continue;
      }
      if (startsNewLine) {
        currentLine = new ArrayList<>();
        lines.add(currentLine);
      }
      for (int point = 0; point < repeatCount; point++) {
        int deltaX = decodeZigzag(commands.get(index));
        int deltaY = decodeZigzag(commands.get(index + 1));
        index += DELTAS_PER_POINT;
        cursorX += deltaX;
        cursorY += deltaY;
        currentLine.add(toWgs84(cursorX, cursorY, extent, tile));
      }
    }
    if (lines.isEmpty()) {
      return null;
    }
    return lines.size() == 1
        ? new GeoJsonGeometry.LineString(lines.get(0))
        : new GeoJsonGeometry.MultiLineString(lines);
  }

  /** Décodage zigzag MVT : les entiers signés sont encodés en non-signés pour le protobuf. */
  private int decodeZigzag(int encoded) {
    return (encoded >> 1) ^ -(encoded & 1);
  }

  /**
   * Reprojette un pixel de tuile (origine coin haut-gauche) vers une coordonnée WGS84 [lon, lat].
   * Formule inverse de la projection Web Mercator (slippy map) : la latitude passe par un
   * atan(sinh) car Mercator n'est pas linéaire en y. Arrondi à 5 décimales (~1 m), suffisant pour
   * la carte et bien plus léger que 7 (~1 cm) sur des centaines de milliers de points.
   */
  private double[] toWgs84(int pixelX, int pixelY, int extent, TileCoordinate tile) {
    int tileCount = 1 << tile.zoom();
    double lon = (tile.tileX() + (double) pixelX / extent) / tileCount * 360.0 - 180.0;
    double lat =
        Math.toDegrees(
            Math.atan(
                Math.sinh(
                    Math.PI * (1 - 2.0 * (tile.tileY() + (double) pixelY / extent) / tileCount))));
    return new double[] {round5(lon), round5(lat)};
  }

  private double round5(double degrees) {
    return Math.round(degrees * 1e5) / 1e5;
  }
}
