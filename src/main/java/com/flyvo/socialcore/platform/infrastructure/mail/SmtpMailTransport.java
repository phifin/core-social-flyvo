package com.flyvo.socialcore.platform.infrastructure.mail;

import com.flyvo.socialcore.platform.application.port.MailTransport;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class SmtpMailTransport implements MailTransport {
  private final JavaMailSender sender;
  private final String from;
  private final String frontend;

  SmtpMailTransport(JavaMailSender sender, Environment env) {
    this.sender = sender;
    from = env.getRequiredProperty("app.mail.from");
    frontend = env.getRequiredProperty("app.auth.frontend-origin");
  }

  public void send(UUID id, String address, String template, String secret) {
    try {
      var message = sender.createMimeMessage();
      message.setFrom(from);
      message.setRecipients(jakarta.mail.Message.RecipientType.TO, address);
      message.setSubject("Social Core: " + template, "UTF-8");
      String body =
          secret.isEmpty()
              ? "SIGN_IN_GUIDANCE".equals(template)
                  ? "An account already uses this email. Sign in at "
                      + frontend
                      + "/auth/sign-in or request a password reset. If you did not request this email, ignore it."
                  : "Your account security settings changed. Sign in to review your account."
              : "Complete "
                  + template
                  + " at "
                  + frontend
                  + "/auth/complete#secret="
                  + secret
                  + ". This proof expires in 30 minutes. Do not share it.";
      message.setText(body, "UTF-8");
      message.saveChanges();
      message.setHeader("Message-ID", "<" + id + "@social-core.invalid>");
      sender.send(message);
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalStateException("MAIL_FAILED");
    }
  }
}
