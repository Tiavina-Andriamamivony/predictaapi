package com.predicta.mg.conf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
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
    assertThat(res.getContentAsByteArray().length).isLessThan(body.length());
    assertThat(gunzip(res.getContentAsByteArray())).isEqualTo(body);
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
