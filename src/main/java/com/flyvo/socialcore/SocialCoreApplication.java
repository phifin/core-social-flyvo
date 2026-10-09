package com.flyvo.socialcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SocialCoreApplication {
  public static void main(String[] args) {
    SpringApplication.run(SocialCoreApplication.class, args);
  }
}
