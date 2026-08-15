package com.predicta.mg.endpoint.mvt;

import com.predicta.mg.endpoint.mvt.mapper.TrafficResultMapper;
import com.predicta.mg.models.QuartierView;
import com.predicta.mg.services.traffic.TrafficService;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TrafficController {

  private static final String GEO_JSON = "application/geo+json";

  private final TrafficService trafficService;

  private final TrafficResultMapper mapper;

  /** retrieve Antananarivo's traffic in geojson */
  @Operation(
      summary = "État du trafic live",
      description =
          "Ville entière (grille disque, lourd) : toutes les tuiles MVT de la source, fusionnées en"
              + " GeoJSON. Best-effort : X-Predicta-Partial si une tuile échoue.")
  @GetMapping(value = "/traffic", produces = GEO_JSON)
  public ResponseEntity<GeoJsonFeatureCollection> traffic() {
    log.info("GET /traffic — fetch + merge live");
    return mapper.geoJson(trafficService.liveGeoJson());
  }

  @Operation(
      summary = "Trafic live autour d'un centroïde",
      description =
          "Grille disque de rayon réduit (zone-radius) recentrée sur le centroïde fourni, pour"
              + " recentrer la carte sur un quartier.")
  @PutMapping(value = "/traffic/zone", produces = GEO_JSON)
  public ResponseEntity<GeoJsonFeatureCollection> getTrafficByCentroid(
      @RequestBody QuartierView quartierView) {
    log.info("PUT /traffic/zone — fetch traffic around {}", quartierView.name());
    return mapper.geoJson(trafficService.liveGeoJsonAround(quartierView));
  }

  /** retrieve the live traffic of one quartier, filtered on its OSM polygon */
  @Operation(
      summary = "Trafic live d'un quartier précis",
      description =
          "Grille polygonale depuis le polygone OSM du quartier puis filtrage des segments sur ce"
              + " quartier (quartierId). 404 si inconnu ; repli disque centroïde si l'index OSM"
              + " n'est pas prêt.")
  @GetMapping(value = "/traffic/quartier/{quartierId}", produces = GEO_JSON)
  public ResponseEntity<GeoJsonFeatureCollection> trafficForQuartier(
      @PathVariable String quartierId) {
    log.info("GET /traffic/quartier/{} — polygon grid + quartierId filter", quartierId);
    return mapper.geoJson(trafficService.liveGeoJsonForQuartier(quartierId));
  }
}
