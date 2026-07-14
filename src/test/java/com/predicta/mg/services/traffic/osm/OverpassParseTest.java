package com.predicta.mg.services.traffic.osm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Parse Overpass sans réseau : fixtures JSON statiques dans {@link #buildQuartiers}/{@link
 * #buildRues}. Le stitching relation → polygone est le point le plus fragile — testé ici.
 */
class OverpassParseTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final GeometryFactory gf = new GeometryFactory();

  private OverpassClient client() {
    // URL/centre/rayon inutilisés par les méthodes de parse ; timeouts arbitraires.
    return new OverpassClient("http://unused", 0, 0, 13, 5, 1000, 1000);
  }

  @Test
  void relation_deux_ways_forme_un_polygone_qui_contient_son_centre() throws Exception {
    // Carré [0,0]-[2,2] fragmenté en 2 ways (bas+droite, haut+gauche).
    String json =
        """
        {"elements":[{"type":"relation","id":42,"members":[
          {"type":"way","geometry":[{"lat":0,"lon":0},{"lat":0,"lon":2},{"lat":2,"lon":2}]},
          {"type":"way","geometry":[{"lat":2,"lon":2},{"lat":2,"lon":0},{"lat":0,"lon":0}]}
        ]}]}
        """;
    STRtree tree = client().buildQuartiers(mapper.readTree(json));
    tree.build();

    Point center = gf.createPoint(new Coordinate(1, 1));
    @SuppressWarnings("unchecked")
    List<QuartierPolygon> hits = tree.query(center.getEnvelopeInternal());
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).quartierId()).isEqualTo("rel_42");
    assertThat(hits.get(0).geometry().contains(center)).isTrue();
  }

  @Test
  void way_nomme_devient_une_rue() throws Exception {
    String json =
        """
        {"elements":[{"type":"way","id":7,"tags":{"name":"Avenue X"},
          "geometry":[{"lat":0,"lon":0},{"lat":0,"lon":1}]}]}
        """;
    STRtree tree = client().buildRues(mapper.readTree(json));
    tree.build();

    assertThat(tree.size()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<NamedRoad> roads = tree.query(new org.locationtech.jts.geom.Envelope(-1, 2, -1, 2));
    assertThat(roads.get(0).name()).isEqualTo("Avenue X");
  }

  @Test
  void way_sans_nom_est_ignore() throws Exception {
    String json =
        """
        {"elements":[{"type":"way","id":8,"tags":{},
          "geometry":[{"lat":0,"lon":0},{"lat":0,"lon":1}]}]}
        """;
    STRtree tree = client().buildRues(mapper.readTree(json));
    assertThat(tree.size()).isZero();
  }
}
