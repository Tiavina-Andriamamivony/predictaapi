package com.predicta.mg.conf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GzipResponseFilterTest {

  private final GzipResponseFilter filter = new GzipResponseFilter();

  @Test
  void compresse_quand_client_accepte_gzip_et_corps_gros() throws Exception {
    String body = "x".repeat(5000);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("Accept-Encoding", "gzip, deflate");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest r,
                  jakarta.servlet.http.HttpServletResponse w)
                  throws IOException {
                w.getOutputStream().write(body.getBytes());
              }
            });

    filter.doFilter(req, res, chain);

    assertThat(res.getHeader("Content-Encoding")).isEqualTo("gzip");
    assertThat(res.getHeader("Vary")).isEqualTo("Accept-Encoding");
    // Contrepartie du streaming : la taille n'est plus connue à l'avance, donc plus de
    // Content-Length (la réponse part en chunked). C'est ce qui évite de matérialiser le corps
    // compressé en mémoire.
    assertThat(res.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
    assertThat(res.getContentAsByteArray().length).isLessThan(body.length());
    assertThat(gunzip(res.getContentAsByteArray())).isEqualTo(body);
  }

  @Test
  void ecrit_le_corps_en_un_seul_appel_de_bloc() throws Exception {
    // Le corps est écrit d'un bloc via write(byte[], int, int) : sans cette surcharge, la version
    // par défaut boucle sur write(int), un appel virtuel par octet.
    byte[] body = "y".repeat(3000).getBytes();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("Accept-Encoding", "gzip");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest r,
                  jakarta.servlet.http.HttpServletResponse w)
                  throws IOException {
                w.getOutputStream().write(body, 0, body.length);
              }
            });

    filter.doFilter(req, res, chain);

    assertThat(gunzip(res.getContentAsByteArray())).isEqualTo(new String(body));
  }

  /** Un corps sous le seuil n'est pas compressé mais doit quand même sortir intact. */
  @Test
  void corps_sous_le_seuil_non_compresse_mais_intact() throws Exception {
    String body = "z".repeat(500);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("Accept-Encoding", "gzip");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest r,
                  jakarta.servlet.http.HttpServletResponse w)
                  throws IOException {
                w.getOutputStream().write(body.getBytes());
              }
            });

    filter.doFilter(req, res, chain);

    assertThat(res.getHeader("Content-Encoding")).isNull();
    assertThat(res.getContentAsString()).isEqualTo(body);
  }

  @Test
  void ne_compresse_pas_sans_accept_encoding() throws Exception {
    String body = "x".repeat(5000);
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest r,
                  jakarta.servlet.http.HttpServletResponse w)
                  throws IOException {
                w.getOutputStream().write(body.getBytes());
              }
            });

    filter.doFilter(req, res, chain);

    assertThat(res.getHeader("Content-Encoding")).isNull();
    assertThat(res.getContentAsString()).isEqualTo(body);
  }

  private static String gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return new String(in.readAllBytes());
    }
  }
}
