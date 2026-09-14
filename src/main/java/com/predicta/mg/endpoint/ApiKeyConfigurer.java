package com.predicta.mg.endpoint;

import com.predicta.mg.repository.ApplicationRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class ApiKeyConfigurer implements WebMvcConfigurer {

  static final String API_KEY_HEADER = "X-API-Key";

  /**
   * Chemins exigeant {@code X-API-Key}. Patterns <b>PathPattern</b>, donc un chemin multi-segment
   * doit finir par {@code /**} : {@code "/traffic"} ne couvre QUE {@code /traffic} — s'il était
   * laissé seul, {@code /traffic/zone} et {@code /traffic/quartier/*} resteraient ouverts (et
   * exposeraient le pipeline lourd, celui qui sature la mémoire, à un appelant anonyme).
   *
   * <p>Exception volontaire : {@code /traffic/tile/**} reste public. C'est la source vectorielle
   * que le navigateur fetch lui-même (comme sur la source trafic) ; la clé de la source amont ne
   * quitte jamais le backend, il n'y a donc rien à protéger côté client.
   */
  static final String[] PROTECTED_PATHS = {
    "/traffic", "/traffic/zone", "/traffic/quartier/**", "/quartiers"
  };

  private final ApplicationRepository applicationRepository;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new ApiKeyInterceptor(applicationRepository))
        .addPathPatterns(PROTECTED_PATHS);
  }

  /** Package-private (et non privée) pour rester testable sans monter un contexte Spring. */
  @RequiredArgsConstructor
  static class ApiKeyInterceptor implements HandlerInterceptor {

    private final ApplicationRepository applicationRepository;

    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      String apiKey = request.getHeader(API_KEY_HEADER);
      if (apiKey != null
          && !apiKey.isBlank()
          && applicationRepository.findByApiKey(apiKey).isPresent()) {
        return true;
      }
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }
  }
}
