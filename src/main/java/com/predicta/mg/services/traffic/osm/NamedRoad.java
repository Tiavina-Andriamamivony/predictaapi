package com.predicta.mg.services.traffic.osm;

import org.locationtech.jts.geom.LineString;

/**
 * Rue OSM nommée : son nom et sa polyligne. Stockée dans le R-tree des rues, servie en
 * plus-proche-voisin pour combler le {@code name} vide d'un segment trafic.
 */
record NamedRoad(String name, LineString line) {}
