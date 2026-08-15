package com.predicta.mg.models;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Grille de tuiles couvrant un polygone (ex. un quartier OSM) au zoom donné : on ne retient que les
 * tuiles dont la bbox <b>intersecte réellement</b> le polygone. Bien plus léger qu'un disque autour
 * du centroïde pour un quartier : typiquement 1-4 tuiles au lieu de 13.
 *
 * <p>Le test d'intersection passe d'abord par les enveloppes (cheap), puis par {@code
 * polygon.intersects(rect)} si les enveloppes se touchent — écarte les tuiles qui ne font que
 * frôler la bbox du polygone sans le couvrir.
 */
public class TileGridSourceFromPolygon implements TileGridSource {

  private final int zoom;
  private final Geometry polygon;
  private final GeometryFactory geometryFactory = new GeometryFactory();

  public TileGridSourceFromPolygon(Geometry polygon, int zoom) {
    this.polygon = polygon;
    this.zoom = zoom;
  }

  @Override
  public List<TileCoordinate> tiles() {
    if (polygon.isEmpty()) {
      return List.of();
    }
    int n = 1 << zoom;
    Envelope env = polygon.getEnvelopeInternal();
    int xMin = Math.max(0, SlippyTiles.tileX(env.getMinX(), zoom));
    int xMax = Math.min(n - 1, SlippyTiles.tileX(env.getMaxX(), zoom));
    int yMin = Math.max(0, SlippyTiles.tileY(env.getMaxY(), zoom));
    int yMax = Math.min(n - 1, SlippyTiles.tileY(env.getMinY(), zoom));
    List<TileCoordinate> tiles = new ArrayList<>();
    for (int x = xMin; x <= xMax; x++) {
      for (int y = yMin; y <= yMax; y++) {
        if (intersects(x, y)) {
          tiles.add(new TileCoordinate(zoom, x, y));
        }
      }
    }
    return tiles;
  }

  /** Intersection réelle polygone vs bbox de la tuile (enveloppes d'abord, puis test exact). */
  private boolean intersects(int x, int y) {
    Envelope tile = SlippyTiles.tileEnvelope(zoom, x, y);
    if (!polygon.getEnvelopeInternal().intersects(tile)) {
      return false;
    }
    return polygon.intersects(geometryFactory.toGeometry(tile));
  }
}
