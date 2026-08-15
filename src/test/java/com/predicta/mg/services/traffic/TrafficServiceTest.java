package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.predicta.mg.conf.ScrapeProps;
import com.predicta.mg.models.Quartier;
import com.predicta.mg.models.QuartierView;
import com.predicta.mg.models.SlippyTiles;
import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import com.predicta.mg.models.TileGridSource;
import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.repository.QuartierRepository;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeature;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.predicta.mg.services.traffic.geojson.SpeedFeatureProperties;
import com.predicta.mg.services.traffic.osm.OsmEnricher;
import com.predicta.mg.services.traffic.osm.OsmIndex;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class TrafficServiceTest {

  private final MvtToGeoJsonConverter converter = new MvtToGeoJsonConverter();
  private final TileGridSource grid = mock(TileGridSource.class);
  private final TileFetcher fetcher = mock(TileFetcher.class);

  private final QuartierRepository quartierRepository = mock(QuartierRepository.class);
  private final OsmIndex osmIndex = mock(OsmIndex.class);
  private final QuartierGeometryIndex geometryIndex = mock(QuartierGeometryIndex.class);
  private final QuartierTrafficCache cache = mock(QuartierTrafficCache.class);

  // Enrichissement OSM no-op : ce test cible l'orchestration fetch/merge/filtre, pas
  // l'enrichissement.
  private final TrafficService service =
      new TrafficService(
          grid,
          fetcher,
          converter,
          OsmEnricher.noop(),
          new ScrapeProps(0, 0, 13, 5, 2, 16),
          quartierRepository,
          osmIndex,
          geometryIndex,
          cache);

  /** Cache transparent : exécute toujours le loader (miss simulé), âge 0. */
  @BeforeEach
  void setUp() {
    when(cache.get(anyString(), any()))
        .thenAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Supplier<TrafficResult> loader = inv.getArgument(1);
              return new QuartierTrafficCache.Cached(loader.get(), 0, false);
            });
  }

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

  private Quartier quartier(String id) {
    Quartier q = new Quartier();
    ReflectionTestUtils.setField(q, "quartierId", id);
    ReflectionTestUtils.setField(q, "name", "Analakely");
    ReflectionTestUtils.setField(q, "source", "osm_admin");
    ReflectionTestUtils.setField(q, "centroidLon", 47.52315);
    ReflectionTestUtils.setField(q, "centroidLat", -18.90457);
    return q;
  }

  /** Polygone inséré dans la tuile (marge ~0,001°) : la grille ne rend que cette tuile. */
  private Geometry tilePolygon(int x, int y) {
    Envelope tile = SlippyTiles.tileEnvelope(13, x, y);
    double inset = 0.001;
    return new GeometryFactory()
        .createPolygon(
            new Coordinate[] {
              new Coordinate(tile.getMinX() + inset, tile.getMinY() + inset),
              new Coordinate(tile.getMaxX() - inset, tile.getMinY() + inset),
              new Coordinate(tile.getMaxX() - inset, tile.getMaxY() - inset),
              new Coordinate(tile.getMinX() + inset, tile.getMaxY() - inset),
              new Coordinate(tile.getMinX() + inset, tile.getMinY() + inset)
            });
  }

  private GeoJsonFeature featureAt(double lon, double lat) {
    return new GeoJsonFeature(
        new SpeedFeatureProperties(null, null, 30, 1.0),
        new GeoJsonGeometry.LineString(List.of(new double[] {lon, lat})));
  }

  /** Enricheur mocké : rend exactement les features données, quel que soit l'input mergé. */
  private OsmEnricher enricherReturning(GeoJsonFeature... features) {
    OsmEnricher enricher = mock(OsmEnricher.class);
    when(enricher.enrich(any())).thenReturn(new GeoJsonFeatureCollection(List.of(features)));
    return enricher;
  }

  private TrafficService with(OsmEnricher enricher) {
    return new TrafficService(
        grid,
        fetcher,
        converter,
        enricher,
        new ScrapeProps(0, 0, 13, 5, 2, 16),
        quartierRepository,
        osmIndex,
        geometryIndex,
        cache);
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

  @Test
  void zone_recentre_la_grille_sur_le_quartier_sans_utiliser_la_grille_par_defaut() {
    when(fetcher.fetch(any())).thenReturn(oneLine());

    TrafficResult result =
        service.liveGeoJsonAround(new QuartierView("Analakely", 47.52315, -18.90457));

    assertThat(result.partial()).isFalse();
    assertThat(result.featureCollection().features()).hasSize(13); // disque r=2
    verify(grid, never()).tiles();
  }

  @Test
  void quartier_grille_polygonale_et_filtre_geometrique() {
    TileCoordinate t = new TileCoordinate(13, 5177, 4534);
    when(quartierRepository.findById("rel_999")).thenReturn(Optional.of(quartier("rel_999")));
    when(osmIndex.quartierGeometryOrNull("rel_999")).thenReturn(tilePolygon(5177, 4534));
    when(fetcher.fetch(eq(t))).thenReturn(oneLine());

    // Un segment dans le polygone + un segment à 10° de là : seul le premier survit au filtre.
    Envelope tile = SlippyTiles.tileEnvelope(13, 5177, 4534);
    double midLon = (tile.getMinX() + tile.getMaxX()) / 2;
    double midLat = (tile.getMinY() + tile.getMaxY()) / 2;
    TrafficService quartierService =
        with(enricherReturning(featureAt(midLon, midLat), featureAt(midLon + 10, midLat)));

    TrafficResult result = quartierService.liveGeoJsonForQuartier("rel_999");

    assertThat(result.partial()).isFalse();
    assertThat(result.featureCollection().features()).hasSize(1);
    double[] kept =
        ((GeoJsonGeometry.LineString) result.featureCollection().features().get(0).geometry())
            .coordinates()
            .get(0);
    assertThat(kept[0]).isEqualTo(midLon);
    assertThat(kept[1]).isEqualTo(midLat);
    verify(grid, never()).tiles();
  }

  @Test
  void quartier_sans_polygone_osm_utilise_la_cellule_voronoi() {
    when(quartierRepository.findById("n_999")).thenReturn(Optional.of(quartier("n_999")));
    when(osmIndex.quartierGeometryOrNull("n_999")).thenReturn(null);
    when(geometryIndex.cellGeometryOrNull("n_999")).thenReturn(tilePolygon(5177, 4534));
    when(fetcher.fetch(any())).thenReturn(oneLine());

    Envelope tile = SlippyTiles.tileEnvelope(13, 5177, 4534);
    double midLon = (tile.getMinX() + tile.getMaxX()) / 2;
    double midLat = (tile.getMinY() + tile.getMaxY()) / 2;
    TrafficService quartierService =
        with(enricherReturning(featureAt(midLon, midLat), featureAt(midLon + 10, midLat)));

    TrafficResult result = quartierService.liveGeoJsonForQuartier("n_999");

    assertThat(result.fallback()).isFalse();
    assertThat(result.featureCollection().features()).hasSize(1);
    verify(grid, never()).tiles();
  }

  @Test
  void quartier_sans_aucune_geometrie_fallback_disque_marque() {
    when(quartierRepository.findById("n_1")).thenReturn(Optional.of(quartier("n_1")));
    when(osmIndex.quartierGeometryOrNull("n_1")).thenReturn(null);
    when(geometryIndex.cellGeometryOrNull("n_1")).thenReturn(null);
    when(fetcher.fetch(any())).thenReturn(oneLine());

    TrafficResult result = service.liveGeoJsonForQuartier("n_1");

    assertThat(result.fallback()).isTrue();
    assertThat(result.partial()).isFalse();
    assertThat(result.featureCollection().features()).hasSize(13); // disque r=2 non filtré
    verify(grid, never()).tiles();
  }

  @Test
  void quartier_inconnu_leve_404() {
    when(quartierRepository.findById("rel_absent")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.liveGeoJsonForQuartier("rel_absent"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
