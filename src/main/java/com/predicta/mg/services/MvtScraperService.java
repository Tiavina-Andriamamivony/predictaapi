package com.predicta.mg.services;

import com.predicta.mg.models.MergedMvtTile;
import com.predicta.mg.models.MvtTile;
import com.predicta.mg.models.TileStatus;
import com.predicta.mg.repository.MergedMvtTileRepository;
import com.predicta.mg.repository.MvtTileRepository;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MvtScraperService {

    private final MvtTileRepository mvtTileRepository;
    private final MergedMvtTileRepository mergedMvtTileRepository;
    private final RestTemplate restTemplate;

    // URLs sources à scraper, séparées par virgule (env SCRAPE_SOURCE_URLS)
    @Value("${scrape.source-urls}")
    private List<String> sourceUrls;

    // Template d'URL des tuiles .pbf (env SCRAPE_TILE_TEMPLATE)
    @Value("${scrape.tile-template}")
    private String mvtTileTemplate;

    @Transactional
    public MergedMvtTile scrapeAndMerge() {
        List<MvtTile> tiles = sourceUrls.stream()
                .map(url -> fetchOrLoad(extractCoordinates(url), url))
                .toList();

        return merge(tiles);
    }

    // -------------------------------------------------------------------------
    // Fetch
    // -------------------------------------------------------------------------

    private MvtTile fetchOrLoad(TileCoordinate coord, String sourceUrl) {
        // Si la tuile existe déjà en base, on la réutilise
        return mvtTileRepository
                .findByZoomAndTileXAndTileY(coord.zoom(), coord.tileX(), coord.tileY())
                .orElseGet(() -> fetchAndSave(coord, sourceUrl));
    }

    private MvtTile fetchAndSave(TileCoordinate coord, String sourceUrl) {
        String url = mvtTileTemplate
                .replace("{zoom}", String.valueOf(coord.zoom()))
                .replace("{x}",    String.valueOf(coord.tileX()))
                .replace("{y}",    String.valueOf(coord.tileY()));

        log.info("Fetching MVT → {}", url);

        var headers = new HttpHeaders();
        headers.set("Accept",     "application/x-protobuf");
        headers.set("User-Agent", "Mozilla/5.0");

        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class
        );

        byte[] data = response.getBody();
        if (data == null || data.length == 0) {
            throw new IllegalStateException("Réponse MVT vide pour : " + url);
        }

        MvtTile tile = MvtTile.builder()
                .zoom(coord.zoom())
                .tileX(coord.tileX())
                .tileY(coord.tileY())
                .sourceUrl(sourceUrl)
                .rawData(data)
                .fetchedAt(LocalDateTime.now())
                .status(TileStatus.FETCHED)
                .build();

        return mvtTileRepository.save(tile);
    }

    // -------------------------------------------------------------------------
    // Merge
    // -------------------------------------------------------------------------

    private MergedMvtTile merge(List<MvtTile> tiles) {
        VectorTile.Tile.Builder builder = VectorTile.Tile.newBuilder();

        for (MvtTile tile : tiles) {
            try {
                VectorTile.Tile parsed = VectorTile.Tile.parseFrom(decompress(tile.getRawData()));
                builder.addAllLayers(parsed.getLayersList());
            } catch (Exception e) {
                throw new RuntimeException(
                        "Échec parsing tile zoom=%d x=%d y=%d"
                                .formatted(tile.getZoom(), tile.getTileX(), tile.getTileY()), e);
            }
        }

        // Mise à jour statut des tuiles sources
        tiles.forEach(t -> {
            t.setStatus(TileStatus.MERGED);
            mvtTileRepository.save(t);
        });

        MergedMvtTile merged = MergedMvtTile.builder()
                .sourceTiles(tiles)
                .mergedData(builder.build().toByteArray())
                .mergedAt(LocalDateTime.now())
                .build();

        return mergedMvtTileRepository.save(merged);
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    private byte[] decompress(byte[] data) throws IOException {
        // Détection magic bytes gzip : 0x1F 0x8B
        if (data.length >= 2
                && (data[0] & 0xFF) == 0x1F
                && (data[1] & 0xFF) == 0x8B) {
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data));
                 var out  = new ByteArrayOutputStream()) {
                gzip.transferTo(out);
                return out.toByteArray();
            }
        }
        return data;
    }

    private TileCoordinate extractCoordinates(String url) {
        // Fragment : #map=zoom/y_mercator/x_mercator/rotation
        String[] parts = url.substring(url.indexOf("map=") + 4).split("/");

        int zoom     = Integer.parseInt(parts[0]);
        double yMerc = Double.parseDouble(parts[1]);
        double xMerc = Double.parseDouble(parts[2]);

        // EPSG:3857 → WGS84
        double lon    = Math.toDegrees(xMerc / 6378137.0);
        double lat    = Math.toDegrees(2 * Math.atan(Math.exp(yMerc / 6378137.0)) - Math.PI / 2);

        // WGS84 → indices de tuile XYZ
        int n         = 1 << zoom;
        int tileX     = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(lat);
        int tileY     = (int) Math.floor(
                (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n
        );

        log.info("Coordonnées extraites → zoom={} tileX={} tileY={}", zoom, tileX, tileY);
        return new TileCoordinate(zoom, tileX, tileY);
    }

    public record TileCoordinate(int zoom, int tileX, int tileY) {}
}