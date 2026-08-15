package com.predicta.mg.services.traffic;

import com.predicta.mg.models.Quartier;
import com.predicta.mg.repository.QuartierRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;
import org.springframework.stereotype.Component;

/**
 * Géométrie de repli pour <b>tous</b> les quartiers de la base : cellules de Voronoi construites
 * depuis les centroïdes (table {@code quartiers}). L'index OSM ne connaît que les relations admin
 * 8|10 ({@code rel_*}) ; les quartiers {@code n_*}/{@code w_*} (cellules Voronoi historiques de
 * l'ancien pipeline) n'ont pas de polygone OSM. Ici on régénère une partition de la zone par le
 * plus proche centroïde : chaque segment est attribué au quartier dont le centroïde est le plus
 * proche — déterministe, couverture 100 % des quartiers, aucun appel réseau.
 *
 * <p>Frontières approximatives (cellules, pas les vraies limites de quartier) — acceptable pour la
 * carte, et bien plus juste qu'un disque centroïde non filtré. Construit une seule fois (les
 * centroïdes sont statiques), lazy et verrouillé ; échec = best-effort (retour null, retente au
 * prochain appel).
 */
@Component
@Slf4j
public class QuartierGeometryIndex {

  // Marge de la bbox de clip (~2 tuiles z13 = 0,044° chacune) : les cellules de bord couvrent
  // large.
  private static final double CLIP_MARGIN_DEG = 0.1;

  private final QuartierRepository quartierRepository;
  private final GeometryFactory geometryFactory = new GeometryFactory();

  private volatile Map<String, Geometry> cells;

  public QuartierGeometryIndex(QuartierRepository quartierRepository) {
    this.quartierRepository = quartierRepository;
  }

  /** Cellule de Voronoi du quartier, ou {@code null} si la construction a échoué. */
  public Geometry cellGeometryOrNull(String quartierId) {
    Map<String, Geometry> index = cells;
    if (index == null) {
      index = build();
    }
    return index == null ? null : index.get(quartierId);
  }

  private synchronized Map<String, Geometry> build() {
    if (cells != null) {
      return cells;
    }
    try {
      List<Quartier> quartiers = quartierRepository.findAll();
      if (quartiers.isEmpty()) {
        log.warn("Aucun quartier en base -> index géométrique vide");
        cells = Map.of();
        return cells;
      }
      Coordinate[] coords = new Coordinate[quartiers.size()];
      double minX = Double.MAX_VALUE;
      double minY = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      for (int i = 0; i < quartiers.size(); i++) {
        Quartier q = quartiers.get(i);
        double lon = q.getCentroidLon();
        double lat = q.getCentroidLat();
        coords[i] = new Coordinate(lon, lat);
        minX = Math.min(minX, lon);
        maxX = Math.max(maxX, lon);
        minY = Math.min(minY, lat);
        maxY = Math.max(maxY, lat);
      }
      // setSites(Geometry) : l'overload Collection de JTS attend en réalité des Coordinate.
      VoronoiDiagramBuilder builder = new VoronoiDiagramBuilder();
      builder.setSites(geometryFactory.createMultiPointFromCoords(coords));
      builder.setClipEnvelope(
          new Envelope(
              minX - CLIP_MARGIN_DEG,
              maxX + CLIP_MARGIN_DEG,
              minY - CLIP_MARGIN_DEG,
              maxY + CLIP_MARGIN_DEG));
      // JTS ne garantit PAS l'ordre des cellules : on attribue chaque cellule au centroïde le plus
      // proche de son point intérieur — le point intérieur d'une cellule de Voronoi est strictement
      // plus proche de son propre site, la correspondance est donc déterministe et non ambiguë.
      Geometry diagram = builder.getDiagram(geometryFactory);
      if (diagram.getNumGeometries() != quartiers.size()) {
        log.warn(
            "Voronoi : {} cellules pour {} quartiers (centroïdes dupliqués ?) — les derniers"
                + " retomberont sur le repli disque",
            diagram.getNumGeometries(),
            quartiers.size());
      }
      Map<String, Geometry> index = new HashMap<>();
      for (int i = 0; i < diagram.getNumGeometries(); i++) {
        Geometry cell = diagram.getGeometryN(i);
        Point interior = cell.getInteriorPoint();
        String bestId = null;
        double bestDist = Double.MAX_VALUE;
        for (int j = 0; j < quartiers.size(); j++) {
          double d = distance2(interior.getCoordinate(), coords[j]);
          if (d < bestDist) {
            bestDist = d;
            bestId = quartiers.get(j).getQuartierId();
          }
        }
        index.put(bestId, cell);
      }
      cells = index;
      log.info("Index géométrique quartiers construit : {} cellules de Voronoi", index.size());
      return cells;
    } catch (Exception e) {
      log.warn("Construction index géométrique échouée (best-effort) : {}", e.getMessage());
      return null;
    }
  }

  /** Distance² (degrés, pas de racine carrée) entre deux coordonnées. */
  private static double distance2(Coordinate a, Coordinate b) {
    double dx = a.x - b.x;
    double dy = a.y - b.y;
    return dx * dx + dy * dy;
  }
}
