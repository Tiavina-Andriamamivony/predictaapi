package com.predicta.mg.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres de la grille de tuiles trafic, liés depuis les propriétés {@code scrape.*}
 * (elles-mêmes alimentées par les variables d'environnement, cf. {@code application.properties}).
 *
 * <p>Aucune valeur par défaut ici : une propriété absente doit faire échouer le démarrage, pas
 * démarrer sur une config muette. Les défauts vivent dans {@code application.properties}
 * (placeholders {@code ${VAR:defaut}}), un seul endroit.
 *
 * @param centerLon longitude du centre de la zone (degrés WGS84)
 * @param centerLat latitude du centre de la zone (degrés WGS84)
 * @param zoom niveau de zoom slippy-map des tuiles
 * @param radius rayon de la grille en tuiles autour du centre (disque)
 * @param fetchParallelism nombre de tuiles fetchées en parallèle
 */
@ConfigurationProperties("scrape")
public record ScrapeProps(
    double centerLon, double centerLat, int zoom, int radius, int fetchParallelism) {}
