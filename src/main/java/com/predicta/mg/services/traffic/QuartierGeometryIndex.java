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
      Coordinate[] sites = centroids(quartiers);
      Geometry diagram = voronoiDiagram(sites);
      Map<String, Geometry> index = assignCells(quartiers, sites, diagram);
      cells = index;
      log.info("Index géométrique quartiers construit : {} cellules de Voronoi", index.size());
      return cells;
    } catch (Exception e) {
      log.warn("Construction index géométrique échouée (best-effort) : {}", e.getMessage());
      return null;
    }
  }

  /** Centroïdes des quartiers, dans l'ordre de la liste (l'index des sites référence cet ordre). */
  private static Coordinate[] centroids(List<Quartier> quartiers) {
    Coordinate[] sites = new Coordinate[quartiers.size()];
    for (int i = 0; i < quartiers.size(); i++) {
      Quartier q = quartiers.get(i);
      sites[i] = new Coordinate(q.getCentroidLon(), q.getCentroidLat());
    }
    return sites;
  }

  /** Diagramme de Voronoi des centroïdes, clippé sur la zone couverte (avec marge). */
  private Geometry voronoiDiagram(Coordinate[] sites) {
    Envelope clip = envelopeWithMargin(sites);
    // setSites(Geometry) : l'overload Collection de JTS attend en réalité des Coordinate.
    VoronoiDiagramBuilder builder = new VoronoiDiagramBuilder();
    builder.setSites(geometryFactory.createMultiPointFromCoords(sites));
    builder.setClipEnvelope(clip);
    return builder.getDiagram(geometryFactory);
  }

  /** Bbox de tous les sites, élargie de la marge de clip (les cellules de bord couvrent large). */
  private static Envelope envelopeWithMargin(Coordinate[] sites) {
    Envelope envelope = new Envelope(sites[0]);
    for (Coordinate site : sites) {
      envelope.expandToInclude(site);
    }
    envelope.expandBy(CLIP_MARGIN_DEG);
    return envelope;
  }

  /**
   * JTS ne garantit PAS l'ordre des cellules : on attribue chaque cellule au centroïde le plus
   * proche de son point intérieur — le point intérieur d'une cellule de Voronoi est strictement
   * plus proche de son propre site, la correspondance est donc déterministe et non ambiguë.
   */
  private static Map<String, Geometry> assignCells(
      List<Quartier> quartiers, Coordinate[] sites, Geometry diagram) {
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
      index.put(nearestQuartierId(cell.getInteriorPoint(), quartiers, sites), cell);
    }
    return index;
  }

  /** Quartier dont le centroïde est le plus proche du point donné. */
  private static String nearestQuartierId(
      Point point, List<Quartier> quartiers, Coordinate[] sites) {
    String bestId = null;
    double bestDist = Double.MAX_VALUE;
    for (int j = 0; j < sites.length; j++) {
      double d = distance2(point.getCoordinate(), sites[j]);
      if (d < bestDist) {
        bestDist = d;
        bestId = quartiers.get(j).getQuartierId();
      }
    }
    return bestId;
  }

  /** Distance² (degrés, pas de racine carrée) entre deux coordonnées. */
  private static double distance2(Coordinate a, Coordinate b) {
    double dx = a.x - b.x;
    double dy = a.y - b.y;
    return dx * dx + dy * dy;
  }
}
