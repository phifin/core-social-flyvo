package com.flyvo.socialcore.identity.infrastructure.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FilterRegistration {
  @Bean
  FilterRegistrationBean<BearerSessionFilter> bearerRegistration(BearerSessionFilter filter) {
    var bean = new FilterRegistrationBean<>(filter);
    bean.setEnabled(false);
    return bean;
  }

  @Bean
  FilterRegistrationBean<BrowserBoundaryFilter> browserRegistration(BrowserBoundaryFilter filter) {
    var bean = new FilterRegistrationBean<>(filter);
    bean.setEnabled(false);
    return bean;
  }
}
