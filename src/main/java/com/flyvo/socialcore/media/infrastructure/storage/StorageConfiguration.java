package com.flyvo.socialcore.media.infrastructure.storage;

import com.flyvo.socialcore.media.application.port.ObjectStorage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.media.enabled", havingValue = "true")
class StorageConfiguration {
  @Bean(destroyMethod = "close")
  StorageClients storageClients(Environment env) throws java.io.IOException {
    var endpoint = URI.create(env.getRequiredProperty("app.media.endpoint"));
    var credentials = new java.util.ArrayList<StaticCredentialsProvider>();
    for (String role : java.util.List.of("api", "worker")) {
      String secret =
          Files.readString(Path.of(env.getRequiredProperty("app.media." + role + "-key-file")))
              .trim();
      if (secret.length() < 32) throw new IllegalArgumentException("STORAGE_KEY_CONFIGURATION");
      credentials.add(
          StaticCredentialsProvider.create(AwsBasicCredentials.create("social-" + role, secret)));
    }
    var api = client(endpoint, credentials.get(0));
    var worker = client(endpoint, credentials.get(1));
    var publicEndpoint =
        URI.create(env.getProperty("app.media.public-endpoint", endpoint.toString()));
    var signer =
        S3Presigner.builder()
            .endpointOverride(publicEndpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials.getFirst())
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build())
            .build();
    return new StorageClients(api, worker, signer);
  }

  @Bean
  ObjectStorage objectStorage(StorageClients c) {
    return new S3ObjectStorage(
        c.api(), c.worker(), c.signer(), "social-quarantine", "social-delivery");
  }

  private S3Client client(URI endpoint, StaticCredentialsProvider credentials) {
    return S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .forcePathStyle(true)
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(3))
                .socketTimeout(Duration.ofSeconds(5)))
        .overrideConfiguration(
            c ->
                c.apiCallTimeout(Duration.ofSeconds(10))
                    .apiCallAttemptTimeout(Duration.ofSeconds(7)))
        .build();
  }

  record StorageClients(S3Client api, S3Client worker, S3Presigner signer)
      implements AutoCloseable {
    public void close() {
      signer.close();
      worker.close();
      api.close();
    }
  }
}
