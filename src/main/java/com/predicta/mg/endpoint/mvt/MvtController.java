package com.predicta.mg.endpoint.mvt;

import com.predicta.mg.models.GeoJsonResult;
import com.predicta.mg.models.MergedMvtTile;
import com.predicta.mg.models.dto.GeoJsonResultResponse;
import com.predicta.mg.models.dto.MergedMvtTileResponse;
import com.predicta.mg.services.MvtScraperService;
import com.predicta.mg.services.MvtToGeoJsonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mvt")
@RequiredArgsConstructor
@Slf4j
public class MvtController {

    private final MvtScraperService mvtScraperService;
    private final MvtToGeoJsonService mvtToGeoJsonService;

    /**
     * Lance le scraping + merge des deux tuiles MVT
     * POST /api/mvt/scrape
     */
    @PostMapping("/scrape")
    public ResponseEntity<MergedMvtTileResponse> scrapeAndMerge() {
        log.info("Démarrage du scraping MVT");
        MergedMvtTile merged = mvtScraperService.scrapeAndMerge();
        return ResponseEntity.ok(MergedMvtTileResponse.from(merged));
    }

    /**
     * Convertit le dernier merge en GeoJSON
     * POST /api/mvt/convert
     */
    @PostMapping("/convert")
    public ResponseEntity<GeoJsonResultResponse> convertToGeoJson() {
        log.info("Démarrage de la conversion GeoJSON");
        GeoJsonResult result = mvtToGeoJsonService.convertLatest();
        return ResponseEntity.ok(GeoJsonResultResponse.from(result));
    }

    /**
     * Scrape + merge + convert en un seul appel
     * POST /api/mvt/scrape-and-convert
     */
    @PostMapping("/scrape-and-convert")
    public ResponseEntity<GeoJsonResultResponse> scrapeAndConvert() {
        log.info("Scraping + conversion GeoJSON en une étape");
        MergedMvtTile merged = mvtScraperService.scrapeAndMerge();
        GeoJsonResult result = mvtToGeoJsonService.convert(merged);
        return ResponseEntity.ok(GeoJsonResultResponse.from(result));
    }

    /**
     * Récupère le dernier GeoJSON converti
     * GET /api/mvt/geojson/latest
     */
    @GetMapping("/geojson/latest")
    public ResponseEntity<String> getLatestGeoJson() {
        return mvtToGeoJsonService.getLatestGeoJson()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}