package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import org.junit.jupiter.api.Test;

class MvtToGeoJsonConverterTest {

  private final MvtToGeoJsonConverter converter = new MvtToGeoJsonConverter();

  /** Construit une tuile MVT avec un layer "speeds" et 1 LineString de 2 points. */
  private byte[] tileWithOneLine(int extent, int px0, int py0, int px1, int py1) {
    VectorTile.Tile.Feature feature =
        VectorTile.Tile.Feature.newBuilder()
            .setType(VectorTile.Tile.GeomType.LINESTRING)
            .addGeometry((1 << 3) | 1)
            .addGeometry(zigzag(px0))
            .addGeometry(zigzag(py0))
            .addGeometry((1 << 3) | 2)
            .addGeometry(zigzag(px1 - px0))
            .addGeometry(zigzag(py1 - py0))
            .build();
    VectorTile.Tile.Layer layer =
        VectorTile.Tile.Layer.newBuilder()
            .setVersion(2)
            .setName("speeds")
            .setExtent(extent)
            .addFeatures(feature)
            .build();
    return VectorTile.Tile.newBuilder().addLayers(layer).build().toByteArray();
  }

  private int zigzag(int n) {
    return (n << 1) ^ (n >> 31);
  }

  private double firstLon(GeoJsonFeatureCollection fc) {
    GeoJsonGeometry.LineString line = (GeoJsonGeometry.LineString) fc.features().get(0).geometry();
    return line.coordinates().get(0)[0];
  }

  @Test
  void deux_tuiles_a_des_tileX_differents_donnent_des_lon_differentes() {
    byte[] mvt = tileWithOneLine(4096, 100, 100, 200, 200);

    GeoJsonFeatureCollection fcA = converter.convert(new TileCoordinate(13, 5177, 4535), mvt);
    GeoJsonFeatureCollection fcB = converter.convert(new TileCoordinate(13, 5178, 4535), mvt);

    double lonA = firstLon(fcA);
    double lonB = firstLon(fcB);

    assertThat(lonB).isGreaterThan(lonA);
    assertThat(lonA).isBetween(47.5049, 47.5489);
    assertThat(lonB).isBetween(47.5489, 47.5929);
  }

  @Test
  void produit_une_feature_collection_avec_une_geometrie_linestring() {
    byte[] mvt = tileWithOneLine(4096, 0, 0, 100, 100);

    GeoJsonFeatureCollection fc = converter.convert(new TileCoordinate(13, 5177, 4535), mvt);

    assertThat(fc.getType()).isEqualTo("FeatureCollection");
    assertThat(fc.features()).hasSize(1);
    var feat = fc.features().get(0);
    assertThat(feat.getType()).isEqualTo("Feature");
    assertThat(feat.geometry()).isInstanceOf(GeoJsonGeometry.LineString.class);
  }

  /** Tuile avec un layer "speeds" (1 ligne) + un layer parasite "osmnodes" (1 point). */
  private byte[] tileSpeedsPlusOsmnodes() {
    VectorTile.Tile.Feature line =
        VectorTile.Tile.Feature.newBuilder()
            .setType(VectorTile.Tile.GeomType.LINESTRING)
            .addGeometry((1 << 3) | 1)
            .addGeometry(zigzag(10))
            .addGeometry(zigzag(10))
            .addGeometry((1 << 3) | 2)
            .addGeometry(zigzag(20))
            .addGeometry(zigzag(20))
            .build();
    VectorTile.Tile.Layer speeds =
        VectorTile.Tile.Layer.newBuilder()
            .setVersion(2)
            .setName("speeds")
            .setExtent(4096)
            .addFeatures(line)
            .build();
    VectorTile.Tile.Feature point =
        VectorTile.Tile.Feature.newBuilder()
            .setType(VectorTile.Tile.GeomType.POINT)
            .addGeometry((1 << 3) | 1)
            .addGeometry(zigzag(50))
            .addGeometry(zigzag(50))
            .build();
    VectorTile.Tile.Layer osmnodes =
        VectorTile.Tile.Layer.newBuilder()
            .setVersion(2)
            .setName("osmnodes")
            .setExtent(4096)
            .addFeatures(point)
            .build();
    return VectorTile.Tile.newBuilder().addLayers(speeds).addLayers(osmnodes).build().toByteArray();
  }

  @Test
  void ne_garde_que_le_layer_speeds_comme_source() {
    // la source ne rend que MVT({layers:"speeds"}) : osmnodes / internal-nodes sont ignorés.
    byte[] mvt = tileSpeedsPlusOsmnodes();

    GeoJsonFeatureCollection fc = converter.convert(new TileCoordinate(13, 5177, 4534), mvt);

    assertThat(fc.features()).hasSize(1);
    var feat = fc.features().get(0);
    assertThat(feat.geometry()).isInstanceOf(GeoJsonGeometry.LineString.class);
  }

  /** Tuile "speeds" avec une feature portant les deux tags conservés (speed, rate). */
  private byte[] tileWithTypedProps() {
    VectorTile.Tile.Layer layer =
        VectorTile.Tile.Layer.newBuilder()
            .setVersion(2)
            .setName("speeds")
            .setExtent(4096)
            .addKeys("speed")
            .addKeys("rate")
            .addKeys("name")
            .addValues(VectorTile.Tile.Value.newBuilder().setIntValue(32))
            .addValues(VectorTile.Tile.Value.newBuilder().setDoubleValue(0.9))
            .addValues(
                VectorTile.Tile.Value.newBuilder().setStringValue("Avenue de l'Indépendance"))
            .addFeatures(
                VectorTile.Tile.Feature.newBuilder()
                    .setType(VectorTile.Tile.GeomType.LINESTRING)
                    .addTags(0)
                    .addTags(0) // speed -> 32
                    .addTags(1)
                    .addTags(1) // rate -> 0.9
                    .addTags(2)
                    .addTags(2) // name -> Avenue de l'Indépendance
                    .addGeometry((1 << 3) | 1)
                    .addGeometry(zigzag(10))
                    .addGeometry(zigzag(10))
                    .addGeometry((1 << 3) | 2)
                    .addGeometry(zigzag(20))
                    .addGeometry(zigzag(20)))
            .build();
    return VectorTile.Tile.newBuilder().addLayers(layer).build().toByteArray();
  }

  @Test
  void decode_les_proprietes_name_speed_et_rate() {
    GeoJsonFeatureCollection fc =
        converter.convert(new TileCoordinate(13, 5177, 4534), tileWithTypedProps());

    var props = fc.features().get(0).properties();
    assertThat(props.name()).isEqualTo("Avenue de l'Indépendance");
    assertThat(props.speed()).isEqualTo(32);
    assertThat(props.rate()).isEqualTo(0.9);
  }
}
