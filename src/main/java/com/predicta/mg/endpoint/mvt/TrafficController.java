package com.predicta.mg.endpoint.mvt;

import com.predicta.mg.models.QuartierView;
import com.predicta.mg.services.traffic.TrafficService;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  // PUT because I want to use RequestBody and it's non conventional to use requestBody inside a GET
  // METHOD
  // Je voudrais avoir le traffic pour une certaine zone, exemple le centre de tana et ses alentours
  // sur 3 kilomètres
  // Notament pour remplacer /traffic qui est très lourd et très couteuse en ressource un
  // lightweighted de /traffic par zone
  @PutMapping("/traffic/zone")
  public ResponseEntity<GeoJsonFeatureCollection> getTrafficByCentroid(
      @RequestBody QuartierView quartierView) {
    log.info("PUT /traffic -fetch traffic for {} zone", quartierView.name());
    throw new RuntimeException("Not Implemented yet");
  }
}
