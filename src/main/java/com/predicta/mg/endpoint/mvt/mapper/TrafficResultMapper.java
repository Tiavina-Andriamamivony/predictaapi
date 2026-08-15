package com.predicta.mg.endpoint.mvt.mapper;

import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class TrafficResultMapper {
  static final String PARTIAL_HEADER = "X-Predicta-Partial";
  static final String FALLBACK_HEADER = "X-Predicta-Fallback";
  static final String AGE_HEADER = "X-Predicta-Age";

  private static final String GEO_JSON = "application/geo+json";

  public ResponseEntity<GeoJsonFeatureCollection> geoJson(TrafficResult result) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok().header("Content-Type", GEO_JSON);
    if (result.partial()) {
      builder.header(PARTIAL_HEADER, "true");
    }
    if (result.fallback()) {
      builder.header(FALLBACK_HEADER, "true");
    }
    if (result.ageMs() > 0) {
      builder.header(AGE_HEADER, String.valueOf(result.ageMs()));
    }
    return builder.body(result.featureCollection());
  }
}
