package com.predicta.mg.models;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Fetch HTTP d'une tuile .pbf via RestTemplate, gunzip si nécessaire. */
@Component
@Slf4j
public class TileFetcherHttp implements TileFetcher {

  private final RestTemplate restTemplate;
  private final String tileTemplate;

  public TileFetcherHttp(
      RestTemplate restTemplate, @Value("${scrape.tile-template}") String tileTemplate) {
    this.restTemplate = restTemplate;
    this.tileTemplate = tileTemplate;
  }

  @Override
  public byte[] fetch(TileCoordinate coord) {
    var url =
        tileTemplate
            .replace("{zoom}", String.valueOf(coord.zoom()))
            .replace("{x}", String.valueOf(coord.tileX()))
            .replace("{y}", String.valueOf(coord.tileY()));
    log.info("Fetching MVT -> {}", url);

    var headers = new HttpHeaders();
    headers.set("Accept", "application/x-protobuf");
    headers.set("User-Agent", "Mozilla/5.0");

    ResponseEntity<byte[]> response =
        restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

    byte[] data = response.getBody();
    if (data == null || data.length == 0) {
      throw new IllegalStateException("Réponse MVT vide pour : " + url);
    }
    try {
      return decompress(data);
    } catch (IOException e) {
      throw new IllegalStateException("Décompression MVT échouée pour : " + url, e);
    }
  }

  private byte[] decompress(byte[] data) throws IOException {
    if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
      try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data));
          var out = new ByteArrayOutputStream()) {
        gzip.transferTo(out);
        return out.toByteArray();
      }
    }
    return data;
  }
}
