package com.predicta.mg.services.traffic.osm;

import org.locationtech.jts.geom.Geometry;

/**
 * Quartier OSM : son id ({@code rel_<osmId>}, parité PK table {@code quartiers}) et sa géométrie
 * (Polygon ou MultiPolygon, trous inclus). Stocké dans le R-tree des quartiers, testé par {@code
 * geometry.contains(point)}.
 */
record QuartierPolygon(String quartierId, Geometry geometry) {}
