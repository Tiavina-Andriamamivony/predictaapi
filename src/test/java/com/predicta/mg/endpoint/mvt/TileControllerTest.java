package com.predicta.mg.endpoint.mvt;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.predicta.mg.models.TileCoordinate;
import com.predicta.mg.models.TileFetcher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TileControllerTest {

  private static final byte[] TILE = "protobuf-bytes".getBytes(StandardCharsets.UTF_8);

  private final TileFetcher fetcher = mock(TileFetcher.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    when(fetcher.fetch(new TileCoordinate(13, 5177, 4534))).thenReturn(TILE);
    mockMvc = MockMvcBuilders.standaloneSetup(new TileController(fetcher)).build();
  }

  @Test
  void tuile_valide_renvoie_le_mvt_avec_etag_et_cache_control() throws Exception {
    mockMvc
        .perform(get("/traffic/tile/13/5177/4534.mvt"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/vnd.mapbox-vector-tile"))
        .andExpect(content().bytes(TILE))
        .andExpect(header().string("Cache-Control", "public, max-age=30"))
        .andExpect(header().string("ETag", matchesPattern("\"[0-9a-f]{64}\"")));
  }

  @Test
  void if_none_match_egale_renvoie_304_sans_corps() throws Exception {
    MvcResult first = mockMvc.perform(get("/traffic/tile/13/5177/4534.mvt")).andReturn();
    String etag = first.getResponse().getHeader("ETag");

    mockMvc
        .perform(get("/traffic/tile/13/5177/4534.mvt").header("If-None-Match", etag))
        .andExpect(status().isNotModified())
        .andExpect(content().bytes(new byte[0]))
        .andExpect(header().string("ETag", etag));
  }

  @Test
  void hors_plage_ou_coordonnees_invalides_renvoie_400_sans_fetch() throws Exception {
    mockMvc.perform(get("/traffic/tile/11/1294/1133.mvt")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/traffic/tile/17/1/1.mvt")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/traffic/tile/13/-1/4534.mvt")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/traffic/tile/13/0/8193.mvt")).andExpect(status().isBadRequest());
    verifyNoInteractions(fetcher);
  }

  @Test
  void echec_du_fetch_renvoie_502() throws Exception {
    when(fetcher.fetch(new TileCoordinate(13, 5178, 4534)))
        .thenThrow(new IllegalStateException("source injoignable"));

    mockMvc.perform(get("/traffic/tile/13/5178/4534.mvt")).andExpect(status().isBadGateway());
  }

  @Test
  void cors_public_sur_les_tuiles() throws Exception {
    // Le préflight OPTIONS est servi par le même @CrossOrigin en production ; le standalone
    // MockMvc n'a pas d'adapter pour PreFlightHandler, on vérifie donc l'en-tête sur le GET.
    mockMvc
        .perform(get("/traffic/tile/13/5177/4534.mvt").header("Origin", "http://localhost:3000"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }
}
