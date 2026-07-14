package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileGridSourceCentered;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileGridSourceCenteredTest {

  // Centre par défaut de la source ( fromLonLat([47.52315,-18.90457]), zoom 16).
  // Au zoom 13, ce centre tombe sur la tuile XYZ (5177, 4534).
  private static final double TANA_LON = 47.52315;
  private static final double TANA_LAT = -18.90457;

  @Test
  void radius_zero_ne_donne_que_la_tuile_centrale() {
    TileGridSourceCentered src = new TileGridSourceCentered(TANA_LON, TANA_LAT, 13, 0);

    List<TileCoordinate> tiles = src.tiles();

    assertThat(tiles).containsExactly(new TileCoordinate(13, 5177, 4534));
  }

  @Test
  void radius_1_donne_le_centre_plus_les_4_voisins_orthogonaux() {
    TileGridSourceCentered src = new TileGridSourceCentered(TANA_LON, TANA_LAT, 13, 1);

    List<TileCoordinate> tiles = src.tiles();

    // Disque r=1 : centre + N/S/E/O, mais PAS les coins (dist^2=2 > 1). 5 tuiles.
    assertThat(tiles)
        .containsExactlyInAnyOrder(
            new TileCoordinate(13, 5177, 4534),
            new TileCoordinate(13, 5176, 4534),
            new TileCoordinate(13, 5178, 4534),
            new TileCoordinate(13, 5177, 4533),
            new TileCoordinate(13, 5177, 4535));
  }

  @Test
  void radius_5_couvre_tout_le_bloc_de_donnees_source() {
    // Bloc de données réel observé sur la source : x 5174..5180, y 4530..4537.
    // En disque il faut r=5 (et non 4) pour englober les bords du bloc.
    TileGridSourceCentered src = new TileGridSourceCentered(TANA_LON, TANA_LAT, 13, 5);

    List<TileCoordinate> tiles = src.tiles();

    for (int x = 5174; x <= 5180; x++) {
      for (int y = 4530; y <= 4537; y++) {
        assertThat(tiles).contains(new TileCoordinate(13, x, y));
      }
    }
    // Les coins du carré englobant sont écartés (disque, pas carré).
    assertThat(tiles).doesNotContain(new TileCoordinate(13, 5172, 4529));
  }

  @Test
  void radius_negatif_leve_exception() {
    assertThatThrownBy(() -> new TileGridSourceCentered(TANA_LON, TANA_LAT, 13, -1).tiles())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radius");
  }
}
