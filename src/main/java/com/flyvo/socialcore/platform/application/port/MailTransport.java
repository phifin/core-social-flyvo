package com.flyvo.socialcore.platform.application.port;

import java.util.UUID;

public interface MailTransport {
  void send(UUID jobId, String address, String template, String secret);
}
