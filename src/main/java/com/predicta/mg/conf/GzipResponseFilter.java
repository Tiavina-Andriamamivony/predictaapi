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
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Compresse gzip la réponse quand le client l'accepte. Nécessaire pour /traffic : le GeoJSON brut
 * fait ~75 Mo, au-delà du plafond 6 Mo de réponse Lambda/API Gateway (-&gt; 502). Gzippé il tombe à
 * ~4 Mo, sans perte. On le fait dans un Filter (et non via {@code server.compression}) car derrière
 * la Lambda il n'y a pas de connecteur Tomcat : seul le pipeline de filtres tourne dans les deux
 * environnements (Tomcat local ET aws-serverless-java-container).
 *
 * <p><b>Budget mémoire.</b> Cette classe est sur le chemin critique des OOM : sur un conteneur 512
 * Mo, une seule réponse /traffic non compressée (~75 Mo sérialisés) suffisait à saturer le heap dès
 * qu'elle était recopiée deux fois (buffer + {@code toByteArray()} + sortie gzip). Le corps est
 * donc compressé <b>en streaming</b> directement vers le flux de la réponse ({@code finish()}, on
 * ne ferme pas le flux du conteneur) : une seule copie du corps existe, celle du buffer, et elle
 * n'est jamais dupliquée. Contrepartie assumée : {@code Content-Length} n'est plus connu à
 * l'avance, la réponse part en {@code chunked} (Tomcat comme aws-serverless-java-container le
 * gèrent).
 */
@Component
@Order(1)
public class GzipResponseFilter implements Filter {

  // En dessous, gzip ne vaut pas le CPU. /traffic est loin au-dessus.
  private static final int MIN_BYTES = 1024;

  /** Tampon du déflateur : 512 o par défaut = beaucoup d'appels de deflate sur ~75 Mo. */
  private static final int GZIP_BUFFER_BYTES = 8192;

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

    ServletOutputStream out = response.getOutputStream();
    if (buffer.size() < MIN_BYTES) {
      // Trop petit pour que gzip vaille le CPU : on recopie tel quel (zéro copie intermédiaire).
      // Pas de flushBuffer() : il committerait la vraie réponse et ferait perdre les en-têtes posés
      // après (Content-Encoding/Content-Length). On écrit directement, Tomcat pose Content-Length.
      buffer.writeTo(out);
      return;
    }

    // En-têtes posés AVANT toute écriture/commit : c'est ce qui garantit qu'ils partent au client.
    response.setHeader("Content-Encoding", "gzip");
    response.addHeader("Vary", "Accept-Encoding");
    GZIPOutputStream gzip = new GZIPOutputStream(out, GZIP_BUFFER_BYTES);
    try {
      buffer.writeTo(gzip);
    } finally {
      gzip.finish();
      out.flush();
    }
  }

  /** Capture le corps écrit par le contrôleur pour pouvoir le gziper en streaming. */
  private static final class BufferingResponse extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ServletOutputStream stream =
        new ServletOutputStream() {
          @Override
          public void write(int b) {
            buffer.write(b);
          }

          /**
           * Indispensable : sans cette surcharge, la version par défaut de {@code
           * ServletOutputStream} boucle sur {@link #write(int)} — un appel virtuel par octet, soit
           * des dizaines de millions d'appels pour /traffic.
           */
          @Override
          public void write(byte[] bytes, int offset, int length) {
            buffer.write(bytes, offset, length);
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
     * réponse serait commitée (en-têtes perdus) AVANT que le filtre pose Content-Encoding et écrive
     * le corps compressé.
     */
    @Override
    public void flushBuffer() {}

    /** Taille du corps capturé, sans le matérialiser (pas de {@code toByteArray()}). */
    int size() {
      return buffer.size();
    }

    /** Recopie le corps capturé vers un flux, sans en faire de copie intermédiaire. */
    void writeTo(OutputStream target) throws IOException {
      buffer.writeTo(target);
    }
  }
}
