package com.predicta.mg.conf;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Compresse gzip la réponse quand le client l'accepte. Nécessaire pour /traffic : le GeoJSON brut
 * fait ~75 Mo, au-delà du plafond 6 Mo de réponse Lambda/API Gateway (-> 502). Gzippé il tombe à ~4
 * Mo, sans perte. On le fait dans un Filter (et non via {@code server.compression}) car derrière la
 * Lambda il n'y a pas de connecteur Tomcat : seul le pipeline de filtres tourne dans les deux
 * environnements (Tomcat local ET aws-serverless-java-container).
 */
@Component
@Order(1)
public class GzipResponseFilter implements Filter {

  // En dessous, gzip ne vaut pas le CPU. /traffic est loin au-dessus.
  private static final int MIN_BYTES = 1024;

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;

    String accept = request.getHeader("Accept-Encoding");
    if (accept == null || !accept.contains("gzip")) {
      chain.doFilter(req, res);
      return;
    }

    BufferingResponse buffer = new BufferingResponse(response);
    chain.doFilter(req, buffer);
    byte[] body = buffer.body();

    if (body.length < MIN_BYTES) {
      // Pas de flushBuffer() : il committerait la vraie réponse et ferait perdre les en-têtes posés
      // après (Content-Encoding/Content-Length). On écrit directement, Tomcat pose Content-Length.
      response.getOutputStream().write(body);
      return;
    }

    // En-têtes posés AVANT toute écriture/commit : c'est ce qui garantit qu'ils partent au client.
    response.setHeader("Content-Encoding", "gzip");
    response.addHeader("Vary", "Accept-Encoding");
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
      gzip.write(body);
    }
    byte[] out = compressed.toByteArray();
    response.setContentLength(out.length);
    response.getOutputStream().write(out);
  }

  /** Capture le corps écrit par le contrôleur pour pouvoir le gziper d'un bloc. */
  private static final class BufferingResponse extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ServletOutputStream stream =
        new ServletOutputStream() {
          @Override
          public void write(int b) {
            buffer.write(b);
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener listener) {}
        };

    BufferingResponse(HttpServletResponse response) {
      super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() {
      return stream;
    }

    /**
     * No-op volontaire : Spring MVC flushe la réponse à la fin du rendu ; sans ce no-op, la vraie
     * réponse serait commitée (en-têtes perdus) AVANT que le filtre pose Content-Encoding et
     * Content-Length et écrive le corps compressé.
     */
    @Override
    public void flushBuffer() {}

    byte[] body() {
      return buffer.toByteArray();
    }
  }
}
