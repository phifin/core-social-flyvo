package com.flyvo.socialcore.platform.infrastructure.security;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SecretConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
