package com.predicta.mg.endpoint.mvt;

import com.predicta.mg.services.traffic.TrafficService;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TrafficController {

  static final String PARTIAL_HEADER = "X-Predicta-Partial";

  private static final String GEO_JSON = "application/geo+json";

  private final TrafficService trafficService;

  /** retrieve Antananarivo's traffic in geojson */
  @GetMapping(value = "/traffic", produces = GEO_JSON)
  public ResponseEntity<GeoJsonFeatureCollection> traffic() {
    log.info("GET /traffic — fetch + merge live");
    var result = trafficService.liveGeoJson();

    ResponseEntity.BodyBuilder builder = ResponseEntity.ok().header("Content-Type", GEO_JSON);
    if (result.partial()) {
      builder.header(PARTIAL_HEADER, "true");
    }
    return builder.body(result.featureCollection());
  }
}
