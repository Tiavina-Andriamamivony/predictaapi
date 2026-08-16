package com.predicta.mg.endpoint.mvt;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import io.swagger.v3.oas.annotations.Operation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tuiles MVT du trafic — passthrough de la source, servies au navigateur comme sur
 * traffic.tag-ip.com : la carte MapLibre fetch elle-même ses tuiles (source « vector »), sans proxy
 * ni aller-retour GeoJSON. Le navigateur ne charge que les tuiles visibles, au bon zoom, et
 * revalide via ETag (304) aux refresh périodiques.
 *
 * <p>Endpoint volontairement public (CORS {@code *}, aucune clé) : comme le serveur de tuiles de
 * référence, ces tuiles sont destinées au navigateur. Le fetch upstream reste côté serveur — la clé
 * de l'application ne quitte jamais le backend. Les endpoints GeoJSON enrichis ({@code /traffic*})
 * restent protégés par {@code X-API-Key}.
 *
 * <p>La compression gzip est assurée par {@code GzipResponseFilter} (pas de double gzip ici).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class TileController {

  private static final String MVT = "application/vnd.mapbox-vector-tile";

  /** Plage de zooms servie par la source upstream (constatée sur traffic.tag-ip.com : 12..16). */
  private static final int MIN_ZOOM = 12;

  private static final int MAX_ZOOM = 16;

  /** La source se met à jour toutes les ~1-2 min : cache court + revalidation ETag (304). */
  private static final String CACHE_CONTROL = "public, max-age=30";

  private final TileFetcher tileFetcher;

  @GetMapping(value = "/traffic/tile/{z}/{x}/{y}.mvt", produces = MVT)
  @Operation(
      summary = "Tuile MVT du trafic live (passthrough de la source, CORS public)",
      description =
          "Octets MVT bruts du layer 'speeds', sans conversion ni enrichissement. Cache-Control +"
              + " ETag pour que MapLibre revalide (304) aux refresh périodiques.")
  public ResponseEntity<byte[]> tile(
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    if (invalid(z, x, y)) {
      log.warn("Tuile hors plage servie : {}/{}/{}", z, x, y);
      return ResponseEntity.badRequest().build();
    }

    final byte[] body;
    try {
      body = tileFetcher.fetch(new TileCoordinate(z, x, y));
    } catch (Exception e) {
      log.warn("Tuile indisponible {}/{}/{} : {}", z, x, y, e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    String etag = etag(body);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    headers.setETag(etag);
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
    }
    return ResponseEntity.ok().headers(headers).body(body);
  }

  private static boolean invalid(int z, int x, int y) {
    if (z < MIN_ZOOM || z > MAX_ZOOM || x < 0 || y < 0) {
      return true;
    }
    int size = 1 << z;
    return x >= size || y >= size;
  }

  /** ETag fort = SHA-256 du corps ; stable quelle que soit l'encodage (gzip côté filtre). */
  private static String etag(byte[] body) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
      return "\"" + HexFormat.of().formatHex(digest) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponible", e);
    }
  }
}
