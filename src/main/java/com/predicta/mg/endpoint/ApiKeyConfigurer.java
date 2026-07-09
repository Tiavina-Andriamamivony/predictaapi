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

  private final ApplicationRepository applicationRepository;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new ApiKeyInterceptor(applicationRepository))
        .addPathPatterns("/traffic", "/quartiers");
  }

  @RequiredArgsConstructor
  private static class ApiKeyInterceptor implements HandlerInterceptor {

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
