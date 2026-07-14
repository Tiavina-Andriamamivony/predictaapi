package com.predicta.mg.services.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcherHttp;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

class TileFetcherHttpTest {

  private final RestTemplate restTemplate = mock(RestTemplate.class);
  private final String template =
      "https://traffic.example.com/tile/v1/car/tile({x},{y},{zoom}).mvt";
  private final TileFetcherHttp fetcher = new TileFetcherHttp(restTemplate, template);

  private byte[] gzip(byte[] data) throws Exception {
    var bos = new ByteArrayOutputStream();
    try (var gz = new GZIPOutputStream(bos)) {
      gz.write(data);
    }
    return bos.toByteArray();
  }

  @Test
  void fetch_template_url_et_decompresse_gzip() throws Exception {
    byte[] payload = {1, 2, 3, 4};
    byte[] gzipped = gzip(payload);
    String expectedUrl = "https://traffic.example.com/tile/v1/car/tile(5177,4535,13).mvt";
    when(restTemplate.exchange(
            eq(expectedUrl), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
        .thenReturn(ResponseEntity.ok(gzipped));

    byte[] out = fetcher.fetch(new TileCoordinate(13, 5177, 4535));

    assertThat(out).containsExactly(1, 2, 3, 4);
  }

  @Test
  void fetch_reponse_vide_leve_exception() {
    when(restTemplate.exchange(
            any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
        .thenReturn(ResponseEntity.ok(new byte[0]));

    try {
      fetcher.fetch(new TileCoordinate(13, 5177, 4535));
      assertThat(false).as("aurait dû lever").isTrue();
    } catch (IllegalStateException expected) {
      assertThat(expected.getMessage()).contains("vide");
    }
  }
}
