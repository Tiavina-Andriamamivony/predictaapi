package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.predicta.mg.models.Quartier;
import com.predicta.mg.repository.QuartierRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.test.util.ReflectionTestUtils;

class QuartierGeometryIndexTest {

  private static final GeometryFactory GF = new GeometryFactory();

  private Quartier quartier(String id, double lon, double lat) {
    Quartier q = new Quartier();
    ReflectionTestUtils.setField(q, "quartierId", id);
    ReflectionTestUtils.setField(q, "name", id);
    ReflectionTestUtils.setField(q, "source", "osm_suburb");
    ReflectionTestUtils.setField(q, "centroidLon", lon);
    ReflectionTestUtils.setField(q, "centroidLat", lat);
    return q;
  }

  @Test
  void chaque_cellule_contient_son_centroide_et_couvre_tous_les_ids() {
    QuartierRepository repo = mock(QuartierRepository.class);
    when(repo.findAll())
        .thenReturn(
            List.of(
                quartier("n_1", 47.50, -18.90),
                quartier("n_2", 47.60, -18.90),
                quartier("n_3", 47.55, -18.85)));

    QuartierGeometryIndex index = new QuartierGeometryIndex(repo);

    // La cellule de Voronoi de chaque quartier contient son propre centroïde (l'ordre des
    // cellules suit l'ordre des sites) et aucun autre id n'est laissé de côté.
    assertThat(index.cellGeometryOrNull("n_1")).isNotNull();
    assertThat(index.cellGeometryOrNull("n_2")).isNotNull();
    assertThat(index.cellGeometryOrNull("n_3")).isNotNull();
    assertThat(coversCentroid(index, "n_1", 47.50, -18.90)).isTrue();
    assertThat(coversCentroid(index, "n_2", 47.60, -18.90)).isTrue();
    assertThat(coversCentroid(index, "n_3", 47.55, -18.85)).isTrue();
    assertThat(index.cellGeometryOrNull("absent")).isNull();
  }

  @Test
  void base_vide_donne_un_index_vide_sans_echec() {
    QuartierRepository repo = mock(QuartierRepository.class);
    when(repo.findAll()).thenReturn(List.of());

    QuartierGeometryIndex index = new QuartierGeometryIndex(repo);

    assertThat(index.cellGeometryOrNull("n_1")).isNull();
  }

  private boolean coversCentroid(QuartierGeometryIndex index, String id, double lon, double lat) {
    Point p = GF.createPoint(new Coordinate(lon, lat));
    return index.cellGeometryOrNull(id).covers(p);
  }
}
