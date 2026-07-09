package com.predicta.mg.services.traffic.geojson;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeoJsonFeatureCollectionTest {

  private GeoJsonFeatureCollection withFeatures(int nb) {
    List<GeoJsonFeature> feats = new ArrayList<>();
    for (int i = 0; i < nb; i++) {
      feats.add(
          new GeoJsonFeature(
              new SpeedFeatureProperties(null, null, 30, 1.0),
              new GeoJsonGeometry.LineString(List.of(new double[] {47.5, -18.9}))));
    }
    return new GeoJsonFeatureCollection(feats);
  }

  @Test
  void concat_concatene_toutes_les_features_sans_perte() {
    GeoJsonFeatureCollection merged =
        GeoJsonFeatureCollection.concat(List.of(withFeatures(2), withFeatures(3)));

    assertThat(merged.getType()).isEqualTo("FeatureCollection");
    assertThat(merged.features()).hasSize(5);
  }

  @Test
  void concat_liste_vide_donne_collection_vide() {
    GeoJsonFeatureCollection merged = GeoJsonFeatureCollection.concat(List.of());

    assertThat(merged.getType()).isEqualTo("FeatureCollection");
    assertThat(merged.features()).isEmpty();
  }

  @Test
  void concat_ignore_les_collections_nulles() {
    GeoJsonFeatureCollection merged =
        GeoJsonFeatureCollection.concat(Arrays.asList(withFeatures(1), null, withFeatures(2)));

    assertThat(merged.features()).hasSize(3);
  }

  @Test
  void empty_donne_une_collection_sans_feature() {
    assertThat(GeoJsonFeatureCollection.empty().features()).isEmpty();
  }

  @Test
  void le_constructeur_protege_contre_un_null() {
    assertThat(new GeoJsonFeatureCollection(null).features()).isEmpty();
  }
}
