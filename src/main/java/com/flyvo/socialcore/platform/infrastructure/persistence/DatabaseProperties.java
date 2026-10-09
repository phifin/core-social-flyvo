package com.flyvo.socialcore.platform.infrastructure.persistence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.database")
public record DatabaseProperties(
    @NotBlank @Pattern(regexp = "jdbc:postgresql://.+", message = "must be a PostgreSQL JDBC URL")
        String url,
    @NotBlank String username,
    @NotBlank String password) {}
