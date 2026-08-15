package com.predicta.mg.services.traffic.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.predicta.mg.services.traffic.geojson.GeoJsonFeature;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.predicta.mg.services.traffic.geojson.SpeedFeatureProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;

class OsmEnricherTest {

  private static final GeometryFactory GF = new GeometryFactory();

  /** Snapshot main : 1 quartier carré [0,0]-[1,1] rel_999, 1 rue nommée le long de y=0.5. */
  private OsmSnapshot snapshot() {
    STRtree quartiers = new STRtree();
    Geometry square =
        GF.createPolygon(
            new Coordinate[] {
              new Coordinate(0, 0),
              new Coordinate(1, 0),
              new Coordinate(1, 1),
              new Coordinate(0, 1),
              new Coordinate(0, 0)
            });
    QuartierPolygon quartier = new QuartierPolygon("rel_999", square);
    quartiers.insert(square.getEnvelopeInternal(), quartier);

    STRtree rues = new STRtree();
    LineString road =
        GF.createLineString(new Coordinate[] {new Coordinate(0, 0.5), new Coordinate(1, 0.5)});
    rues.insert(road.getEnvelopeInternal(), new NamedRoad("Rue Test", road));

    quartiers.build();
    rues.build();
    return new OsmSnapshot(
        quartiers, Map.of("rel_999", quartier), rues, System.currentTimeMillis());
  }

  private OsmEnricher enricherWith(OsmSnapshot snap) {
    OsmIndex index = mock(OsmIndex.class);
    when(index.snapshotOrNull()).thenReturn(snap);
    return new OsmEnricher(index, 30);
  }

  private GeoJsonFeature feature(String name, double lon, double lat) {
    return new GeoJsonFeature(
        new SpeedFeatureProperties(name, null, 30, 1.0),
        new GeoJsonGeometry.LineString(List.of(new double[] {lon, lat})));
  }

  @Test
  void segment_dans_quartier_avec_name_vide_recoit_quartier_et_name() {
    // Point sur la rue (y=0.5) -> name comblé ; dans le carré -> quartier posé.
    GeoJsonFeatureCollection out =
        enricherWith(snapshot())
            .enrich(new GeoJsonFeatureCollection(List.of(feature(null, 0.5, 0.5))));

    SpeedFeatureProperties p = out.features().get(0).properties();
    assertThat(p.quartierId()).isEqualTo("rel_999");
    assertThat(p.name()).isEqualTo("Rue Test");
  }

  @Test
  void segment_hors_quartier_et_loin_reste_intact() {
    GeoJsonFeatureCollection out =
        enricherWith(snapshot())
            .enrich(new GeoJsonFeatureCollection(List.of(feature(null, 5.0, 5.0))));

    SpeedFeatureProperties p = out.features().get(0).properties();
    assertThat(p.quartierId()).isNull();
    assertThat(p.name()).isNull();
  }

  @Test
  void name_deja_present_preserve_mais_quartier_pose() {
    GeoJsonFeatureCollection out =
        enricherWith(snapshot())
            .enrich(new GeoJsonFeatureCollection(List.of(feature("Nom Source", 0.5, 0.5))));

    SpeedFeatureProperties p = out.features().get(0).properties();
    assertThat(p.name()).isEqualTo("Nom Source");
    assertThat(p.quartierId()).isEqualTo("rel_999");
  }

  @Test
  void snapshot_null_rend_la_collection_inchangee() {
    GeoJsonFeatureCollection in = new GeoJsonFeatureCollection(List.of(feature(null, 0.5, 0.5)));

    GeoJsonFeatureCollection out = enricherWith(null).enrich(in);

    assertThat(out.features().get(0).properties().quartierId()).isNull();
    assertThat(out.features().get(0).properties().name()).isNull();
  }

  @Test
  void noop_est_identite() {
    GeoJsonFeatureCollection in = new GeoJsonFeatureCollection(List.of(feature(null, 0.5, 0.5)));

    assertThat(OsmEnricher.noop().enrich(in)).isSameAs(in);
  }
}
