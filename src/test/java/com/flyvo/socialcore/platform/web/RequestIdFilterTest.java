package com.flyvo.socialcore.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void propagatesValidIdAndClearsMdcEvenOnFailure() {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    request.addHeader(RequestIdFilter.HEADER, "client-request_123");
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      assertThat(MDC.get("requestId")).isEqualTo("client-request_123");
                      throw new ServletException("test failure");
                    }))
        .isInstanceOf(ServletException.class);
    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-request_123");
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void replacesUnsafeOrOversizedIds() throws Exception {
    for (String value : new String[] {"bad\r\nheader", "a".repeat(65), "", "email@example.org"}) {
      var request = new MockHttpServletRequest();
      var response = new MockHttpServletResponse();
      request.addHeader(RequestIdFilter.HEADER, value);
      filter.doFilter(request, response, (req, res) -> {});
      assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER))).isNotNull();
    }
  }
}
