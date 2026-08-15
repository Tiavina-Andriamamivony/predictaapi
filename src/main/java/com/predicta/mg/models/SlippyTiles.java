package com.predicta.mg.models;

import org.locationtech.jts.geom.Envelope;

/**
 * Projection Web Mercator slippy-map (tuiles XYZ) : conversions lon/lat &harr; tuile et bbox de
 * tuile vers WGS84. Formules partagées par toutes les sources de grille ({@link
 * TileGridSourceCentered}, {@link TileGridSourceFromPolygon}).
 */
public final class SlippyTiles {

  /** Niveau de zoom max courant (résolution max). */
  public static final int MAX_ZOOM = 22;

  private SlippyTiles() {}

  /** Colonne de tuile contenant la longitude (degrés WGS84) au zoom donné. */
  public static int tileX(double lon, int zoom) {
    return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
  }

  /** Ligne de tuile contenant la latitude (degrés WGS84) au zoom donné. */
  public static int tileY(double lat, int zoom) {
    double latRad = Math.toRadians(lat);
    double mercatorY = Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad));
    return (int) Math.floor((1.0 - mercatorY / Math.PI) / 2.0 * (1 << zoom));
  }

  /** Bbox WGS84 (minLon, maxLon, minLat, maxLat) couverte par la tuile (x, y) au zoom donné. */
  public static Envelope tileEnvelope(int zoom, int x, int y) {
    int n = 1 << zoom;
    double minLon = x / (double) n * 360.0 - 180.0;
    double maxLon = (x + 1) / (double) n * 360.0 - 180.0;
    double maxLat = toLat(y, n);
    double minLat = toLat(y + 1, n);
    return new Envelope(minLon, maxLon, minLat, maxLat);
  }

  /** Inverse Mercator : latitude (degrés) du bord supérieur de la ligne y (sur n lignes). */
  private static double toLat(int y, int n) {
    return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / n))));
  }
}
