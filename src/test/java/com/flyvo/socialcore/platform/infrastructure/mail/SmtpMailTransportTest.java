package com.flyvo.socialcore.platform.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;

class SmtpMailTransportTest {
  static class CapturingSender extends JavaMailSenderImpl {
    MimeMessage captured;

    @Override
    public void send(MimeMessage message) {
      captured = message;
    }
  }

  @Test
  void existingEmailGetsSignInGuidanceNotAFabricatedSecurityChange() throws Exception {
    var sender = new CapturingSender();
    var transport =
        new SmtpMailTransport(
            sender,
            new MockEnvironment()
                .withProperty("app.mail.from", "no-reply@example.invalid")
                .withProperty("app.auth.frontend-origin", "https://app.example.invalid"));
    var id = UUID.randomUUID();
    transport.send(id, "fixture@example.org", "SIGN_IN_GUIDANCE", "");
    assertThat(sender.captured.getContent().toString())
        .contains("Sign in", "password reset", "ignore it")
        .doesNotContain("settings changed", "#secret=");
    assertThat(sender.captured.getHeader("Message-ID", null))
        .isEqualTo("<" + id + "@social-core.invalid>");
  }

  @Test
  void mailboxProofUsesAFrontendFragmentAndAnIdempotentMessageId() throws Exception {
    var sender = new CapturingSender();
    var transport =
        new SmtpMailTransport(
            sender,
            new MockEnvironment()
                .withProperty("app.mail.from", "no-reply@example.invalid")
                .withProperty("app.auth.frontend-origin", "https://app.example.invalid"));
    var id = UUID.randomUUID();
    transport.send(id, "fixture@example.org", "VERIFY_EMAIL", "fixture-proof");
    assertThat(sender.captured.getContent().toString())
        .contains("/auth/complete#secret=fixture-proof")
        .doesNotContain("?secret=");
    var first = sender.captured.getHeader("Message-ID", null);
    transport.send(id, "fixture@example.org", "VERIFY_EMAIL", "fixture-proof");
    assertThat(sender.captured.getHeader("Message-ID", null)).isEqualTo(first);
  }
}
