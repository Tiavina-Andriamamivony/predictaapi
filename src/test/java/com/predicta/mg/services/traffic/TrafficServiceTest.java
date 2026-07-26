package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.predicta.mg.conf.ScrapeProps;
import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import com.predicta.mg.models.TileGridSource;
import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.services.traffic.osm.OsmEnricher;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficServiceTest {

  private final MvtToGeoJsonConverter converter = new MvtToGeoJsonConverter();
  private final TileGridSource grid = mock(TileGridSource.class);
  private final TileFetcher fetcher = mock(TileFetcher.class);

  // Enrichissement OSM no-op : ce test cible l'orchestration fetch/merge, pas l'enrichissement.
  private final TrafficService service =
      new TrafficService(
          grid, fetcher, converter, OsmEnricher.noop(), new ScrapeProps(0, 0, 13, 5, 16));

  private byte[] oneLine() {
    VectorTile.Tile.Feature f =
        VectorTile.Tile.Feature.newBuilder()
            .setType(VectorTile.Tile.GeomType.LINESTRING)
            .addGeometry((1 << 3) | 1)
            .addGeometry(zz(10))
            .addGeometry(zz(10))
            .addGeometry((1 << 3) | 2)
            .addGeometry(zz(20))
            .addGeometry(zz(20))
            .build();
    VectorTile.Tile.Layer l =
        VectorTile.Tile.Layer.newBuilder()
            .setVersion(2)
            .setName("speeds")
            .setExtent(4096)
            .addFeatures(f)
            .build();
    return VectorTile.Tile.newBuilder().addLayers(l).build().toByteArray();
  }

  private int zz(int n) {
    return (n << 1) ^ (n >> 31);
  }

  @Test
  void nominal_toutes_tuiles_ok_partial_false() {
    TileCoordinate t1 = new TileCoordinate(13, 5177, 4535);
    TileCoordinate t2 = new TileCoordinate(13, 5177, 4534);
    when(grid.tiles()).thenReturn(List.of(t1, t2));
    when(fetcher.fetch(eq(t1))).thenReturn(oneLine());
    when(fetcher.fetch(eq(t2))).thenReturn(oneLine());

    TrafficResult result = service.liveGeoJson();

    assertThat(result.partial()).isFalse();
    assertThat(result.featureCollection().features()).hasSize(2);
  }

  @Test
  void best_effort_une_tuile_echoue_partial_true() {
    TileCoordinate t1 = new TileCoordinate(13, 5177, 4535);
    TileCoordinate t2 = new TileCoordinate(13, 5177, 4534);
    when(grid.tiles()).thenReturn(List.of(t1, t2));
    when(fetcher.fetch(eq(t1))).thenReturn(oneLine());
    when(fetcher.fetch(eq(t2))).thenThrow(new IllegalStateException("timeout"));

    TrafficResult result = service.liveGeoJson();

    assertThat(result.partial()).isTrue();
    assertThat(result.featureCollection().features()).hasSize(1);
  }
}
