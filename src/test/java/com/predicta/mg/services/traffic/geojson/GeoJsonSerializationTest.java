package com.predicta.mg.services.traffic.geojson;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test de contrat : la sérialisation Jackson du modèle typé doit reproduire EXACTEMENT le format
 * GeoJSON attendu par les consommateurs (la carte / l'UI). La référence est le golden {@code
 * traffic/speeds-sample.geojson}. Garde-fou contre toute dérive du format de sortie de {@code
 * /traffic} (noms snake_case, ordre des champs sémantiquement neutre, types des valeurs).
 */
class GeoJsonSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private GeoJsonFeatureCollection sample() {
    GeoJsonFeature line =
        new GeoJsonFeature(
            new SpeedFeatureProperties(null, null, 32, 0.9),
            new GeoJsonGeometry.LineString(
                List.of(
                    new double[] {47.5502014, -18.9511131},
                    new double[] {47.549032, -18.9520771})));
    GeoJsonFeature multi =
        new GeoJsonFeature(
            new SpeedFeatureProperties(null, null, 12, 0.4),
            new GeoJsonGeometry.MultiLineString(
                List.of(
                    List.of(new double[] {47.51, -18.91}, new double[] {47.52, -18.92}),
                    List.of(new double[] {47.53, -18.93}, new double[] {47.54, -18.94}))));
    return new GeoJsonFeatureCollection(List.of(line, multi));
  }

  @Test
  void le_modele_type_se_serialise_exactement_comme_le_golden_geojson() throws Exception {
    JsonNode expected;
    try (InputStream in = getClass().getResourceAsStream("/traffic/speeds-sample.geojson")) {
      assertThat(in).as("golden fixture présent").isNotNull();
      expected = mapper.readTree(in);
    }

    JsonNode actual = mapper.valueToTree(sample());

    // Comparaison structurelle (insensible à l'ordre des clés d'objet, sensible aux
    // valeurs/tableaux).
    assertThat(actual).isEqualTo(expected);
  }
}
