package com.predicta.mg.services.traffic;

import com.predicta.mg.conf.ScrapeProps;
import com.predicta.mg.models.Quartier;
import com.predicta.mg.models.QuartierView;
import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import com.predicta.mg.models.TileGridSource;
import com.predicta.mg.models.TileGridSourceCentered;
import com.predicta.mg.models.TileGridSourceFromPolygon;
import com.predicta.mg.models.TrafficResult;
import com.predicta.mg.repository.QuartierRepository;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeature;
import com.predicta.mg.services.traffic.geojson.GeoJsonFeatureCollection;
import com.predicta.mg.services.traffic.geojson.GeoJsonGeometry;
import com.predicta.mg.services.traffic.osm.OsmEnricher;
import com.predicta.mg.services.traffic.osm.OsmIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestration live de /traffic : grille &rarr; fetch &rarr; convert (par tuile) &rarr; merge
 * GeoJSON &rarr; enrichissement OSM. Aucune persistence.
 *
 * <p>Best-effort : une tuile en échec est ignorée (log warn) et marque le résultat partiel. Le
 * fetch est parallélisé car la grille couvrant Tana fait plusieurs dizaines de tuiles ; en
 * séquentiel (~0,65 s/tuile pleine) on dépasserait le plafond 30 s de l'API Gateway.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficService {

  private final TileGridSource tileGridSource;
  private final TileFetcher tileFetcher;
  private final MvtToGeoJsonConverter converter;
  private final OsmEnricher osmEnricher;
  private final ScrapeProps props;
  private final QuartierRepository quartierRepository;
  private final OsmIndex osmIndex;
  private final QuartierGeometryIndex quartierGeometryIndex;
  private final QuartierTrafficCache quartierTrafficCache;
  private final GeometryFactory geometryFactory = new GeometryFactory();

  /** Résultat interne du fetch parallèle : les collections récoltées + si au moins une a échoué. */
  private record FetchOutcome(List<GeoJsonFeatureCollection> collections, boolean partial) {}

  /** Trafic live de toute la zone couverte par la grille par défaut (toute Tana). */
  public TrafficResult liveGeoJson() {
    return liveGeoJson(tileGridSource.tiles());
  }

  /**
   * Trafic live d'un quartier précis, servi par le cache TTL : grille <b>polygonale</b> depuis la
   * géométrie du quartier (polygone OSM si dispo, sinon cellule de Voronoi), puis filtrage
   * géométrique des segments dans cette géométrie. Typiquement 1-4 tuiles au lieu des 13 du disque
   * centroïde. Sans aucune géométrie, repli disque centroïde non filtré (marqué {@code fallback}).
   * 404 si le quartier n'existe pas en base.
   */
  public TrafficResult liveGeoJsonForQuartier(String quartierId) {
    QuartierTrafficCache.Cached cached =
        quartierTrafficCache.get(quartierId, () -> loadQuartier(quartierId));
    TrafficResult result = cached.result();
    if (cached.ageMs() > 0) {
      return new TrafficResult(
          result.featureCollection(), result.partial(), result.fallback(), cached.ageMs());
    }
    return result;
  }

  /**
   * Chargement effectif (appelé par le cache au miss et au refresh) : géométrie, grille, filtre.
   */
  private TrafficResult loadQuartier(String quartierId) {
    Quartier quartier =
        quartierRepository
            .findById(quartierId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Quartier inconnu : " + quartierId));
    Geometry geometry = osmIndex.quartierGeometryOrNull(quartierId);
    if (geometry == null) {
      geometry = quartierGeometryIndex.cellGeometryOrNull(quartierId);
    }
    if (geometry == null) {
      log.warn(
          "Aucune géométrie (OSM ni Voronoi) pour {} -> disque centroïde non filtré (fallback)",
          quartierId);
      TrafficResult disk = liveGeoJsonAround(QuartierView.of(quartier));
      return new TrafficResult(disk.featureCollection(), disk.partial(), true, 0);
    }
    List<TileCoordinate> tiles = new TileGridSourceFromPolygon(geometry, props.zoom()).tiles();
    return filterByGeometry(liveGeoJson(tiles), geometry);
  }

  /**
   * Ne garde que les segments dont le 1er point tombe dans la géométrie du quartier. Filtrage
   * géométrique (pas par tag) : fonctionne pour tous les quartiers, même sans enrichissement OSM.
   */
  private TrafficResult filterByGeometry(TrafficResult result, Geometry geometry) {
    PreparedGeometry prepared = PreparedGeometryFactory.prepare(geometry);
    List<GeoJsonFeature> kept = new ArrayList<>();
    for (GeoJsonFeature f : result.featureCollection().features()) {
      double[] pt = firstCoord(f.geometry());
      if (pt != null
          && prepared.covers(geometryFactory.createPoint(new Coordinate(pt[0], pt[1])))) {
        kept.add(f);
      }
    }
    return new TrafficResult(new GeoJsonFeatureCollection(kept), result.partial());
  }

  /** Premier point [lon, lat] de la géométrie du segment, ou null si vide. */
  private double[] firstCoord(GeoJsonGeometry geometry) {
    if (geometry instanceof GeoJsonGeometry.LineString ls && !ls.coordinates().isEmpty()) {
      return ls.coordinates().get(0);
    }
    if (geometry instanceof GeoJsonGeometry.MultiLineString mls
        && !mls.coordinates().isEmpty()
        && !mls.coordinates().get(0).isEmpty()) {
      return mls.coordinates().get(0).get(0);
    }
    return null;
  }

  /**
   * Trafic live d'une zone centrée sur un quartier : même pipeline que {@link #liveGeoJson()}, mais
   * sur une grille disque recentrée sur le quartier et de rayon réduit ({@code zone-radius}). Bien
   * plus léger que la couverture complète, pour un recentrage carte.
   */
  public TrafficResult liveGeoJsonAround(QuartierView quartier) {
    // Grille recentrée sur le quartier : le rayon de couverture (radius) prend le zone-radius
    // réduit.
    TileGridSource zone =
        new TileGridSourceCentered(
            new ScrapeProps(
                quartier.lon(),
                quartier.lat(),
                props.zoom(),
                props.zoneRadius(),
                props.zoneRadius(),
                props.fetchParallelism()));
    return liveGeoJson(zone.tiles());
  }

  /** Cœur commun : fetch parallèle des tuiles données, merge, enrichissement, log. */
  private TrafficResult liveGeoJson(List<TileCoordinate> tiles) {
    FetchOutcome outcome = fetchAllTiles(tiles);
    GeoJsonFeatureCollection merged = merge(outcome.collections());
    log.info(
        "/traffic live : {} tuiles, {} features, partial={}",
        tiles.size(),
        merged.features().size(),
        outcome.partial());
    return new TrafficResult(merged, outcome.partial());
  }

  /** Fetch + conversion de toutes les tuiles en parallèle ; possède le cycle de vie du pool. */
  private FetchOutcome fetchAllTiles(List<TileCoordinate> tiles) {
    int threads = Math.clamp(tiles.size(), 1, props.fetchParallelism());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<GeoJsonFeatureCollection>> futures = new ArrayList<>();
      for (TileCoordinate coord : tiles) {
        futures.add(pool.submit(fetchAndConvert(coord)));
      }
      return collect(futures, tiles);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Récolte les résultats du pool ; ignore les tuiles en échec (best-effort) et note le partiel.
   */
  private FetchOutcome collect(
      List<Future<GeoJsonFeatureCollection>> futures, List<TileCoordinate> tiles) {
    List<GeoJsonFeatureCollection> collections = new ArrayList<>();
    boolean partial = false;
    for (int i = 0; i < tiles.size(); i++) {
      try {
        collections.add(futures.get(i).get());
      } catch (ExecutionException e) {
        partial = true;
        log.warn("Tuile ignorée (best-effort) {} : {}", tiles.get(i), e.getCause().getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        partial = true;
        log.warn("Fetch interrompu pour {} : {}", tiles.get(i), e.getMessage());
      }
    }
    return new FetchOutcome(collections, partial);
  }

  /** Une tuile : fetch du .pbf puis conversion en GeoJSON, avec sa propre coordonnée de tuile. */
  private Callable<GeoJsonFeatureCollection> fetchAndConvert(TileCoordinate coord) {
    return () -> converter.convert(coord, tileFetcher.fetch(coord));
  }

  /**
   * Concatène les collections puis pose quartierId + noms via OSM (best-effort, no-op possible).
   */
  private GeoJsonFeatureCollection merge(List<GeoJsonFeatureCollection> collections) {
    return osmEnricher.enrich(GeoJsonFeatureCollection.concat(collections));
  }
}
