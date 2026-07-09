package com.predicta.mg.services.traffic.osm;

import com.predicta.mg.services.traffic.geojson.GeoJsonFeature;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.predicta.mg.services.traffic.geojson.SpeedFeatureProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Enrichit une {@link GeoJsonFeatureCollection} trafic avec OSM : pose un {@code quartierId}
 * (point-in-polygon sur les polygones admin) et comble un {@code name} vide depuis la rue nommée la
 * plus proche.
 *
 * <p>Best-effort strict : si l'index OSM n'est pas prêt (snapshot null), désactivé, ou si quoi que
 * ce soit rate, la collection d'entrée est rendue inchangée. Ne lève jamais — {@code /traffic} ne
 * dépend pas de cet enrichissement.
 *
 * <p>Point représentatif d'un segment = 1er point de la 1re ligne (O(1)). Suffisant : les segments
 * trafic sont courts et locaux à un quartier.
 */
@Component
@Slf4j
public class OsmEnricher {

  private final OsmIndex index;
  private final double nameMaxDistM;
  private final GeometryFactory geometryFactory = new GeometryFactory();

  public OsmEnricher(
      OsmIndex index, @Value("${scrape.osm.name-max-dist-m:30}") double nameMaxDistM) {
    this.index = index;
    this.nameMaxDistM = nameMaxDistM;
  }

  /** Enrichisseur no-op (identité) : pour les tests qui n'exercent pas OSM. */
  public static OsmEnricher noop() {
    return new OsmEnricher(null, 0);
  }

  public GeoJsonFeatureCollection enrich(GeoJsonFeatureCollection input) {
    if (index == null) {
      return input;
    }
    try {
      OsmSnapshot snap = index.snapshotOrNull();
      if (snap == null) {
        return input;
      }
      List<GeoJsonFeature> out = new ArrayList<>(input.features().size());
      for (GeoJsonFeature f : input.features()) {
        out.add(enrichOne(f, snap));
      }
      return new GeoJsonFeatureCollection(out);
    } catch (Throwable t) {
      log.warn("Enrichissement OSM ignoré (best-effort) : {}", t.getMessage());
      return input;
    }
  }

  private GeoJsonFeature enrichOne(GeoJsonFeature feature, OsmSnapshot snap) {
    double[] pt = firstCoord(feature.geometry());
    if (pt == null) {
      return feature;
    }
    SpeedFeatureProperties props = feature.properties();
    String quartierId = findQuartier(snap.quartiers(), pt[0], pt[1]);
    String name = props.name();
    if (name == null || name.isBlank()) {
      name = findRoadName(snap.rues(), pt[0], pt[1]);
    }
    if (quartierId == null && name == props.name()) {
      return feature; // rien à ajouter
    }
    return new GeoJsonFeature(
        new SpeedFeatureProperties(name, quartierId, props.speed(), props.rate()),
        feature.geometry());
  }

  /** Premier point [lon, lat] de la géométrie, ou null si vide. */
  private double[] firstCoord(GeoJsonGeometry geometry) {
    if (geometry instanceof GeoJsonGeometry.LineString ls && !ls.coordinates().isEmpty()) {
      return ls.coordinates().get(0);
    }
    if (geometry instanceof GeoJsonGeometry.MultiLineString mls
        && !mls.coordinates().isEmpty()
        && !mls.coordinates().get(0).isEmpty()) {
      return mls.coordinates().get(0).get(0);
    }
    return null;
  }

  private String findQuartier(STRtree quartiers, double lon, double lat) {
    if (quartiers.isEmpty()) {
      return null;
    }
    Point p = geometryFactory.createPoint(new Coordinate(lon, lat));
    @SuppressWarnings("unchecked")
    List<QuartierPolygon> candidates = quartiers.query(new Envelope(lon, lon, lat, lat));
    for (QuartierPolygon q : candidates) {
      if (q.geometry().contains(p)) {
        return q.quartierId();
      }
    }
    return null;
  }

  private String findRoadName(STRtree rues, double lon, double lat) {
    if (rues.isEmpty()) {
      return null;
    }
    Point p = geometryFactory.createPoint(new Coordinate(lon, lat));
    NamedRoad nearest =
        (NamedRoad) rues.nearestNeighbour(p.getEnvelopeInternal(), p, ROAD_DISTANCE);
    if (nearest == null) {
      return null;
    }
    double meters = haversineM(lat, lon, nearest.line());
    return meters <= nameMaxDistM ? nearest.name() : null;
  }

  /** Distance R-tree : le point candidat vs la ligne stockée dans {@link NamedRoad}. */
  private static final org.locationtech.jts.index.strtree.ItemDistance ROAD_DISTANCE =
      new org.locationtech.jts.index.strtree.ItemDistance() {
        @Override
        public double distance(
            org.locationtech.jts.index.strtree.ItemBoundable a,
            org.locationtech.jts.index.strtree.ItemBoundable b) {
          Object ia = a.getItem();
          Object ib = b.getItem();
          var ga = ia instanceof NamedRoad nr ? nr.line() : (org.locationtech.jts.geom.Geometry) ia;
          var gb = ib instanceof NamedRoad nr ? nr.line() : (org.locationtech.jts.geom.Geometry) ib;
          return ga.distance(gb); // degrés : suffisant pour ORDONNER les candidats
        }
      };

  /**
   * Distance en mètres du point au segment de rue le plus proche. La distance JTS est en degrés
   * (planaire) — juste pour ordonner ; le vrai seuil mètres passe par haversine sur le point de la
   * ligne le plus proche.
   */
  private double haversineM(double lat, double lon, org.locationtech.jts.geom.LineString line) {
    Coordinate nearest =
        org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(
            line, geometryFactory.createPoint(new Coordinate(lon, lat)))[0];
    double r = 6371000.0;
    double dLat = Math.toRadians(nearest.y - lat);
    double dLon = Math.toRadians(nearest.x - lon);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(nearest.y))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }
}
