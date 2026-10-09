package com.flyvo.archfixtures.content.application;

import com.flyvo.archfixtures.account.infrastructure.SecretRepository;

public class ForbiddenUseCase {
  public SecretRepository violateBoundary() {
    return new SecretRepository();
  }
}
