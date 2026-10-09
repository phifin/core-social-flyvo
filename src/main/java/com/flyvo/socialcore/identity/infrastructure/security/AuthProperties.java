package com.flyvo.socialcore.identity.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotBlank String frontendOrigin,
    Boolean secureCookies,
    String signingKeyId,
    String privateKeyFile,
    String publicKeyFile,
    String retainedPublicKeys) {
  public AuthProperties {
    if (secureCookies == null) secureCookies = true;
    if (signingKeyId == null) signingKeyId = "primary";
    if (retainedPublicKeys == null) retainedPublicKeys = "";
  }
}
