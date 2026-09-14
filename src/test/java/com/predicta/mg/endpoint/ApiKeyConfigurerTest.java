package com.predicta.mg.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.predicta.mg.models.Application;
import com.predicta.mg.repository.ApplicationRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

class ApiKeyConfigurerTest {

  private static final PathPatternParser PARSER = new PathPatternParser();

  /**
   * Chemins qui DOIVENT exiger la clé : ce sont les endpoints qui déclenchent le fetch de tuiles et
   * le merge GeoJSON (le poste mémoire du service). Un appelant anonyme ne doit pas pouvoir les
   * atteindre.
   */
  static Stream<String> cheminsProteges() {
    return Stream.of(
        "/traffic",
        "/traffic/zone",
        "/traffic/quartier/rel_123",
        "/traffic/quartier/n_1",
        "/quartiers");
  }

  /**
   * Le passthrough MVT reste public : le navigateur fetch ses tuiles, la clé amont reste côté
   * serveur.
   */
  static Stream<String> cheminsPublics() {
    return Stream.of("/traffic/tile/13/5177/4534.mvt", "/traffic/tile/16/1/1.mvt", "/ping");
  }

  @ParameterizedTest
  @MethodSource("cheminsProteges")
  void les_endpoints_lourds_sont_couverts_par_un_pattern(String path) {
    assertThat(matchesAny(PROTECTED, path)).as("chemin protégé %s", path).isTrue();
  }

  @ParameterizedTest
  @MethodSource("cheminsPublics")
  void le_passthrough_mvt_reste_public(String path) {
    assertThat(matchesAny(PROTECTED, path)).as("chemin public %s", path).isFalse();
  }

  @Test
  void sans_cle_la_requete_est_rejetee_en_401() throws Exception {
    ApiKeyConfigurer.ApiKeyInterceptor interceptor =
        new ApiKeyConfigurer.ApiKeyInterceptor(mock(ApplicationRepository.class));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/traffic/quartier/rel_1");

    assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "cle-inconnue"})
  void une_cle_absente_vide_ou_inconnue_est_rejetee(String apiKey) throws Exception {
    ApplicationRepository repository = mock(ApplicationRepository.class);
    when(repository.findByApiKey(apiKey)).thenReturn(Optional.empty());
    ApiKeyConfigurer.ApiKeyInterceptor interceptor =
        new ApiKeyConfigurer.ApiKeyInterceptor(repository);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ApiKeyConfigurer.API_KEY_HEADER, apiKey);
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void une_cle_valide_laisse_passer() throws Exception {
    ApplicationRepository repository = mock(ApplicationRepository.class);
    when(repository.findByApiKey("bonne-cle")).thenReturn(Optional.of(mock(Application.class)));
    ApiKeyConfigurer.ApiKeyInterceptor interceptor =
        new ApiKeyConfigurer.ApiKeyInterceptor(repository);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(ApiKeyConfigurer.API_KEY_HEADER, "bonne-cle");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
  }

  /**
   * Les patterns sont évalués avec le <b>même</b> moteur que Spring MVC ({@link PathPattern}) : un
   * {@code /traffic} sans {@code /**} ne matche pas {@code /traffic/zone}, c'est exactement la
   * régression qui laissait ces endpoints ouverts.
   */
  private static final PathPattern[] PROTECTED =
      Stream.of(ApiKeyConfigurer.PROTECTED_PATHS).map(PARSER::parse).toArray(PathPattern[]::new);

  private static boolean matchesAny(PathPattern[] patterns, String path) {
    PathContainer container = PathContainer.parsePath(path);
    for (PathPattern pattern : patterns) {
      if (pattern.matches(container)) {
        return true;
      }
    }
    return false;
  }
}
