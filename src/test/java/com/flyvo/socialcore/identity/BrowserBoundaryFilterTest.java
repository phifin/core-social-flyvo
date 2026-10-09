package com.flyvo.socialcore.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.flyvo.socialcore.identity.infrastructure.security.BrowserBoundaryFilter;
import com.flyvo.socialcore.platform.api.Secrets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.HandlerExceptionResolver;

class BrowserBoundaryFilterTest {
  @Test
  void rejectsInvalidOAuthBudgetsAtStartup() {
    for (String property : List.of("oauth-authorize-per-minute", "oauth-callback-per-minute")) {
      for (String value : List.of("0", "-1", "1001")) {
        var env =
            new MockEnvironment()
                .withProperty("app.auth.frontend-origin", "https://social-core.invalid")
                .withProperty("app.auth." + property, value);
        assertThatThrownBy(
                () ->
                    new BrowserBoundaryFilter(
                        env,
                        mock(HandlerExceptionResolver.class),
                        mock(Secrets.class),
                        Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("OAuth rate limits must be between 1 and 1000 per minute");
      }
    }
  }
}
