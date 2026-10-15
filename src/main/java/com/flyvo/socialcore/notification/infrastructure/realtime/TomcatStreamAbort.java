package com.flyvo.socialcore.notification.infrastructure.realtime;

import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/** Container-owned abort: AsyncContext.complete alone may wait for a blocked final flush. */
@Component
class TomcatStreamAbort implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
  static final String ATTRIBUTE = TomcatStreamAbort.class.getName();

  public void customize(TomcatServletWebServerFactory factory) {
    factory.addContextValves(
        new ValveBase(true) {
          public void invoke(Request request, Response response)
              throws IOException, jakarta.servlet.ServletException {
            if ("GET".equals(request.getMethod())
                && "/api/v1/notifications/stream".equals(request.getRequestPathMB().toString())) {
              request.setAttribute(
                  ATTRIBUTE,
                  (Runnable)
                      () ->
                          response
                              .getCoyoteResponse()
                              .action(
                                  ActionCode.CLOSE_NOW, new IOException("STREAM_BACKPRESSURE")));
            }
            getNext().invoke(request, response);
          }
        });
  }
}
