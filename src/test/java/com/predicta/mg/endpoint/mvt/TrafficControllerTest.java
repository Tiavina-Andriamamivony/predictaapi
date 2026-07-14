package com.predicta.mg.endpoint.mvt;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.services.traffic.TrafficService;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeature;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.predicta.mg.services.traffic.geojson.SpeedFeatureProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrafficControllerTest {

  private final TrafficService service = mock(TrafficService.class);
  private MockMvc mockMvc;

  private GeoJsonFeatureCollection oneFeature() {
    GeoJsonFeature feature =
        new GeoJsonFeature(
            new SpeedFeatureProperties(null, null, 32, 0.9),
            new GeoJsonGeometry.LineString(
                List.of(
                    new double[] {47.5502014, -18.9511131},
                    new double[] {47.549032, -18.9520771})));
    return new GeoJsonFeatureCollection(List.of(feature));
  }

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TrafficController(service)).build();
  }

  @Test
  void traffic_complet_renvoie_200_geojson_sans_header_partial() throws Exception {
    when(service.liveGeoJson()).thenReturn(new TrafficResult(oneFeature(), false));

    mockMvc
        .perform(get("/traffic"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/geo+json"))
        .andExpect(header().doesNotExist("X-Predicta-Partial"))
        .andExpect(jsonPath("$.type").value("FeatureCollection"))
        .andExpect(jsonPath("$.features[0].type").value("Feature"))
        .andExpect(jsonPath("$.features[0].properties.speed").value(32))
        .andExpect(jsonPath("$.features[0].properties.rate").value(0.9))
        .andExpect(jsonPath("$.features[0].geometry.type").value("LineString"));
  }

  @Test
  void traffic_partiel_pose_header_partial() throws Exception {
    when(service.liveGeoJson())
        .thenReturn(new TrafficResult(GeoJsonFeatureCollection.empty(), true));

    mockMvc
        .perform(get("/traffic"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Predicta-Partial", "true"));
  }
}
