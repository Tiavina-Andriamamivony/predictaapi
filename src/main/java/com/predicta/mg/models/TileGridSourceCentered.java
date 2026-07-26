package com.predicta.mg.models;

import com.predicta.mg.conf.ScrapeProps;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Construit la grille de tuiles couvrant la zone, en <b>disque</b> : la ville est ronde autour d'un
 * centre (lon/lat), on garde toutes les tuiles dont la distance au centre (mesurée en tuiles) est
 * &le; {@code radius}. Plus le rayon est grand, plus la couverture s'étend. Le disque (plutôt qu'un
 * carré) écarte les quatre coins — campagne vide — donc moins de fetch et une réponse plus légère.
 *
 * <p>Config par défaut alimentée par la source trafic : centre ({@code 47.52315, -18.90457}), zoom
 * 13, tuile centrale (5177, 4534). Les tuiles vides restantes sont ignorées best-effort en aval.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TileGridSourceCentered implements TileGridSource {

  // Bornes du niveau de zoom slippy-map : 0 = monde entier, 22 = résolution max courante.
  private static final int MAX_ZOOM = 22;

  private final ScrapeProps props;

  @Override
  public List<TileCoordinate> tiles() {
    checkConfig();
    TileCoordinate center = centerTile();
    return diskAround(center);
  }

  /** Rejette une config invalide au plus tôt, avec un message pointant la propriété fautive. */
  private void checkConfig() {
    if (props.radius() < 0) {
      throw new IllegalArgumentException("scrape.radius doit être >= 0, reçu " + props.radius());
    }
    if (props.zoom() < 0 || props.zoom() > MAX_ZOOM) {
      throw new IllegalArgumentException(
          "scrape.zoom doit être dans [0.." + MAX_ZOOM + "], reçu " + props.zoom());
    }
  }

  /** Projette le centre (lon/lat WGS84) sur sa tuile XYZ via la projection Web Mercator directe. */
  private TileCoordinate centerTile() {
    int tilesPerAxis = 1 << props.zoom();
    int tileX = (int) Math.floor((props.centerLon() + 180.0) / 360.0 * tilesPerAxis);
    double latRad = Math.toRadians(props.centerLat());
    double mercatorY = Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad));
    int tileY = (int) Math.floor((1.0 - mercatorY / Math.PI) / 2.0 * tilesPerAxis);
    return new TileCoordinate(props.zoom(), tileX, tileY);
  }

  /** Balaye la bbox carrée autour du centre et ne retient que les tuiles tombant dans le disque. */
  private List<TileCoordinate> diskAround(TileCoordinate center) {
    int radius = props.radius();
    List<TileCoordinate> disk = new ArrayList<>();
    for (int x = center.tileX() - radius; x <= center.tileX() + radius; x++) {
      for (int y = center.tileY() - radius; y <= center.tileY() + radius; y++) {
        if (isInsideDisk(x - center.tileX(), y - center.tileY(), radius)) {
          disk.add(new TileCoordinate(props.zoom(), x, y));
        }
      }
    }
    logGrid(center, disk.size());
    return disk;
  }

  /** Distance² (en tuiles) au centre &le; rayon². Tout en entiers : pas de racine carrée. */
  public static boolean isInsideDisk(int deltaX, int deltaY, int radius) {
    return deltaX * deltaX + deltaY * deltaY <= radius * radius;
  }

  private void logGrid(TileCoordinate center, int tileCount) {
    log.info(
        "Grille disque z{} centre({},{}) radius={} -> {} tuiles",
        props.zoom(),
        center.tileX(),
        center.tileY(),
        props.radius(),
        tileCount);
  }
}
