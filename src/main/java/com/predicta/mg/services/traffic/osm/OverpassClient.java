package com.predicta.mg.services.traffic.osm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.locationtech.jts.operation.polygonize.Polygonizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Fetch OSM via l'API Overpass et construction de l'index spatial ({@link OsmSnapshot}).
 *
 * <p>Deux requêtes {@code [out:json]} sur la bbox de la zone couverte :
 *
 * <ul>
 *   <li>relations {@code boundary=administrative} niveaux 8/10 → polygones de quartier ;
 *   <li>ways {@code highway} nommés → polylignes de rue.
 * </ul>
 *
 * <p>Best-effort de bout en bout : toute erreur (réseau, 429, parse) remonte en exception que
 * {@link OsmIndex} avale — {@code /traffic} n'en dépend jamais. À l'intérieur d'un load réussi, une
 * relation/way individuelle qui échoue est ignorée (log warn), pas tout le lot.
 *
 * <p>Le {@link RestClient} est dédié (timeout long, ~20 s) : les tuiles trafic gardent leur bean 3
 * s/5 s à part, un fetch OSM lent ne doit pas les contaminer.
 */
@Component
@Slf4j
public class OverpassClient {

  private final RestClient http;
  private final ObjectMapper mapper = new ObjectMapper();
  private final GeometryFactory geometryFactory = new GeometryFactory();
  private final String overpassUrl;
  private final double centerLon;
  private final double centerLat;
  private final int zoom;
  private final int radius;

  public OverpassClient(
      @Value("${scrape.osm.overpass-url:https://overpass-api.de/api/interpreter}")
          String overpassUrl,
      @Value("${scrape.center-lon:47.52315}") double centerLon,
      @Value("${scrape.center-lat:-18.90457}") double centerLat,
      @Value("${scrape.zoom:13}") int zoom,
      @Value("${scrape.radius:5}") int radius,
      @Value("${scrape.osm.http-connect-ms:3000}") int connectMs,
      @Value("${scrape.osm.http-read-ms:20000}") int readMs) {
    this.overpassUrl = overpassUrl;
    this.centerLon = centerLon;
    this.centerLat = centerLat;
    this.zoom = zoom;
    this.radius = radius;
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Duration.ofMillis(connectMs).toMillis());
    factory.setReadTimeout((int) Duration.ofMillis(readMs).toMillis());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Charge les deux R-trees. Lève en cas d'échec global (le load complet a raté) ; c'est à
   * l'appelant ({@link OsmIndex}) d'avaler.
   */
  OsmSnapshot load() {
    String bbox = bbox();
    STRtree quartiers = buildQuartiers(post(quartiersQuery(bbox)));
    STRtree rues = buildRues(post(ruesQuery(bbox)));
    quartiers.build();
    rues.build();
    log.info(
        "Index OSM chargé : {} quartiers, {} rues (bbox {})", quartiers.size(), rues.size(), bbox);
    return new OsmSnapshot(quartiers, rues, System.currentTimeMillis());
  }

  private JsonNode post(String query) {
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("data", query);
      String body =
          http.post()
              .uri(overpassUrl)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(String.class);
      return mapper.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException("Requête Overpass échouée : " + e.getMessage(), e);
    }
  }

  private String quartiersQuery(String bbox) {
    return "[out:json][timeout:60];"
        + "relation[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(8|10)$\"]("
        + bbox
        + ");out body geom;";
  }

  private String ruesQuery(String bbox) {
    return "[out:json][timeout:60];way[\"highway\"][\"name\"](" + bbox + ");out geom;";
  }

  STRtree buildQuartiers(JsonNode root) {
    STRtree tree = new STRtree();
    for (JsonNode el : root.path("elements")) {
      if (!"relation".equals(el.path("type").asText())) {
        continue;
      }
      try {
        Geometry geom = relationToPolygon(el);
        if (geom == null || geom.isEmpty()) {
          continue;
        }
        String id = "rel_" + el.path("id").asLong();
        tree.insert(geom.getEnvelopeInternal(), new QuartierPolygon(id, geom));
      } catch (Exception e) {
        log.warn("Relation OSM {} ignorée : {}", el.path("id").asLong(), e.getMessage());
      }
    }
    return tree;
  }

  /**
   * Stitche les ways-membres d'une relation en polygone(s). Les membres {@code outer} peuvent être
   * fragmentés en plusieurs ways : {@link LineMerger} les recolle, {@link Polygonizer} ferme les
   * anneaux.
   *
   * <p>ponytail: les anneaux {@code inner} (trous/enclaves) sont fusionnés comme les outer et
   * comblés, pas soustraits. Un segment dans une enclave est donc taggé au quartier englobant au
   * lieu de null — écart mineur pour du tag de quartier. Passer à Polygon(shell, holes) si un
   * quartier à enclave doit rester null.
   */
  private Geometry relationToPolygon(JsonNode relation) {
    LineMerger merger = new LineMerger();
    for (JsonNode member : relation.path("members")) {
      if (!"way".equals(member.path("type").asText())) {
        continue;
      }
      List<Coordinate> coords = geometryCoords(member.path("geometry"));
      if (coords.size() >= 2) {
        merger.add(geometryFactory.createLineString(coords.toArray(new Coordinate[0])));
      }
    }
    Polygonizer polygonizer = new Polygonizer();
    polygonizer.add(merger.getMergedLineStrings());
    @SuppressWarnings("unchecked")
    var polys = (java.util.Collection<Polygon>) polygonizer.getPolygons();
    if (polys.isEmpty()) {
      return null;
    }
    return geometryFactory
        .createGeometryCollection(polys.toArray(new Polygon[0]))
        .union(); // union = MultiPolygon ou Polygon selon le nombre d'anneaux
  }

  STRtree buildRues(JsonNode root) {
    STRtree tree = new STRtree();
    for (JsonNode el : root.path("elements")) {
      if (!"way".equals(el.path("type").asText())) {
        continue;
      }
      try {
        String name = el.path("tags").path("name").asText(null);
        List<Coordinate> coords = geometryCoords(el.path("geometry"));
        if (name == null || name.isBlank() || coords.size() < 2) {
          continue;
        }
        LineString line = geometryFactory.createLineString(coords.toArray(new Coordinate[0]));
        tree.insert(line.getEnvelopeInternal(), new NamedRoad(name, line));
      } catch (Exception e) {
        log.warn("Way OSM {} ignoré : {}", el.path("id").asLong(), e.getMessage());
      }
    }
    return tree;
  }

  /** Décode un tableau {@code geometry} Overpass ({@code [{lat,lon},...]}) en coordonnées JTS. */
  private List<Coordinate> geometryCoords(JsonNode geometry) {
    List<Coordinate> coords = new ArrayList<>();
    for (JsonNode pt : geometry) {
      if (pt.has("lat") && pt.has("lon")) {
        coords.add(new Coordinate(pt.get("lon").asDouble(), pt.get("lat").asDouble()));
      }
    }
    return coords;
  }

  /**
   * Bbox Overpass {@code south,west,north,east} dérivée du centre + rayon (en tuiles au zoom). On
   * élargit d'une tuile : sur-fetcher est sans conséquence, sous-fetcher couperait des quartiers de
   * bord.
   */
  private String bbox() {
    int n = 1 << zoom;
    double half = (radius + 1) * 360.0 / n;
    double south = centerLat - half;
    double north = centerLat + half;
    double west = centerLon - half;
    double east = centerLon + half;
    return south + "," + west + "," + north + "," + east;
  }
}
