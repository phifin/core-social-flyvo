package com.flyvo.socialcore.platform.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(DatabaseProperties properties) {
    var source = new HikariDataSource();
    source.setJdbcUrl(properties.url());
    source.setUsername(properties.username());
    source.setPassword(properties.password());
    return source;
  }
}
