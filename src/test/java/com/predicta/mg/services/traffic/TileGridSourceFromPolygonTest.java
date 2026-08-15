package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import com.predicta.mg.models.SlippyTiles;
import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileGridSourceFromPolygon;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

class TileGridSourceFromPolygonTest {

  private static final int ZOOM = 13;
  private static final GeometryFactory GF = new GeometryFactory();

  /** Polygone inséré dans la tuile (marge ~0,001°) : ne touche aucune tuile voisine. */
  private Geometry inside(int x, int y) {
    Envelope tile = SlippyTiles.tileEnvelope(ZOOM, x, y);
    double inset = 0.001;
    return GF.createPolygon(
        new Coordinate[] {
          new Coordinate(tile.getMinX() + inset, tile.getMinY() + inset),
          new Coordinate(tile.getMaxX() - inset, tile.getMinY() + inset),
          new Coordinate(tile.getMaxX() - inset, tile.getMaxY() - inset),
          new Coordinate(tile.getMinX() + inset, tile.getMaxY() - inset),
          new Coordinate(tile.getMinX() + inset, tile.getMinY() + inset)
        });
  }

  @Test
  void polygone_interieur_a_une_tuile_ne_donne_que_cette_tuile() {
    List<TileCoordinate> tiles = new TileGridSourceFromPolygon(inside(5177, 4534), ZOOM).tiles();

    assertThat(tiles).containsExactly(new TileCoordinate(ZOOM, 5177, 4534));
  }

  @Test
  void polygone_pose_sur_le_coin_de_4_tuiles_les_couvre_toutes() {
    // Petit carré centré sur le coin commun de (5176,4533), (5176,4534), (5177,4533), (5177,4534) :
    // il touche les 4 tuiles -> toutes retenues (intersection, pas juste la bbox).
    Envelope corner = SlippyTiles.tileEnvelope(ZOOM, 5177, 4534);
    double lon = corner.getMinX();
    double lat = corner.getMaxY();
    Geometry square =
        GF.createPolygon(
            new Coordinate[] {
              new Coordinate(lon - 0.001, lat + 0.001),
              new Coordinate(lon + 0.001, lat + 0.001),
              new Coordinate(lon + 0.001, lat - 0.001),
              new Coordinate(lon - 0.001, lat - 0.001),
              new Coordinate(lon - 0.001, lat + 0.001)
            });

    List<TileCoordinate> tiles = new TileGridSourceFromPolygon(square, ZOOM).tiles();

    assertThat(tiles)
        .containsExactlyInAnyOrder(
            new TileCoordinate(ZOOM, 5176, 4533),
            new TileCoordinate(ZOOM, 5176, 4534),
            new TileCoordinate(ZOOM, 5177, 4533),
            new TileCoordinate(ZOOM, 5177, 4534));
  }

  @Test
  void polygone_vide_donne_aucune_tuile() {
    List<TileCoordinate> tiles =
        new TileGridSourceFromPolygon(GF.createPolygon(new Coordinate[0]), ZOOM).tiles();

    assertThat(tiles).isEmpty();
  }
}
