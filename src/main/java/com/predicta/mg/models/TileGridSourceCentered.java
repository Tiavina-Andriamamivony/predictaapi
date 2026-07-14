package com.predicta.mg.models;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Construit la grille de tuiles couvrant la zone à partir d'un centre (lon/lat) + un rayon, en
 * disque : la ville est ronde autour du centre, on prend toutes les tuiles dont la distance au
 * centre (en tuiles) est <= radius. Plus le radius est grand, plus la couverture s'étend.
 *
 * <p>Valeurs par défaut = config observée sur la source trafic : centre {@code
 * fromLonLat([47.52315,-18.90457])}, zoom mini 13, tuile centrale (5177,4534) au zoom 13. Les
 * tuiles vides (coins/campagne) sont de toute façon ignorées best-effort en aval.
 */
@Component
@Slf4j
public class TileGridSourceCentered implements TileGridSource {

  private final double centerLon;
  private final double centerLat;
  private final int zoom;
  private final int radius;

  public TileGridSourceCentered(
      @Value("${scrape.center-lon:47.52315}") double centerLon,
      @Value("${scrape.center-lat:-18.90457}") double centerLat,
      @Value("${scrape.zoom:13}") int zoom,
      @Value("${scrape.radius:5}") int radius) {
    this.centerLon = centerLon;
    this.centerLat = centerLat;
    this.zoom = zoom;
    this.radius = radius;
  }

  @Override
  public List<TileCoordinate> tiles() {
    if (radius < 0) {
      throw new IllegalArgumentException("radius doit être >= 0 (scrape.radius)");
    }

    TileCoordinate center = centerTile();
    int minX = center.tileX() - radius;
    int maxX = center.tileX() + radius;
    int minY = center.tileY() - radius;
    int maxY = center.tileY() + radius;

    // Disque (et non carré) : on garde la tuile si sa distance au centre <= radius, en tuiles.
    // La ville est ronde autour du centre ; ça écarte les 4 coins (zones de campagne vides) et
    // réduit d'autant le nombre de fetch et la taille de la réponse.
    List<TileCoordinate> grid = new ArrayList<>();
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        int dx = x - center.tileX();
        int dy = y - center.tileY();
        if (dx * dx + dy * dy <= radius * radius) {
          grid.add(new TileCoordinate(zoom, x, y));
        }
      }
    }
    log.info(
        "Grille disque z{} centre({},{}) x[{}..{}] y[{}..{}] radius={} -> {} tuiles",
        zoom,
        center.tileX(),
        center.tileY(),
        minX,
        maxX,
        minY,
        maxY,
        radius,
        grid.size());
    return grid;
  }

  private TileCoordinate centerTile() {
    int n = 1 << zoom;
    int tileX = (int) Math.floor((centerLon + 180.0) / 360.0 * n);
    double latRad = Math.toRadians(centerLat);
    int tileY =
        (int)
            Math.floor(
                (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
    return new TileCoordinate(zoom, tileX, tileY);
  }
}
