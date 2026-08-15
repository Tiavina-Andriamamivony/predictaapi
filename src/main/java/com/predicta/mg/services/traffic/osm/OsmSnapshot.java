package com.predicta.mg.services.traffic.osm;

import java.util.Map;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Instantané immuable de l'index OSM : deux R-trees construits une fois puis lus en lock-free. Le
 * champ {@code loadedAtMs} sert au TTL. Publié via {@code volatile} dans {@link OsmIndex} : une
 * fois construit, jamais muté.
 *
 * <p>{@code quartiers} indexe des {@link QuartierPolygon} par enveloppe de polygone (query =
 * point-in-polygon candidat). {@code rues} indexe des {@link NamedRoad} par enveloppe de ligne
 * (query = plus-proche-voisin pour combler un nom vide). {@code quartierById} est la même liste
 * indexée par id ({@code rel_<osmId>}) : lookup O(1) pour l'endpoint trafic d'un quartier précis.
 */
record OsmSnapshot(
    STRtree quartiers, Map<String, QuartierPolygon> quartierById, STRtree rues, long loadedAtMs) {}
