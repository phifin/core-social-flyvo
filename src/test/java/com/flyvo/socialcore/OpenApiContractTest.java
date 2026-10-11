package com.flyvo.socialcore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class OpenApiContractTest {
  @Test
  void implementedRoutesParseWithoutDuplicateKeysAndAllLocalReferencesResolve() throws Exception {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Map<String, Object> api =
        new Yaml(new SafeConstructor(options))
            .load(Files.readString(Path.of("docs/api/openapi.yaml")));
    assertThat(api.get("openapi")).isEqualTo("3.1.0");
    var paths = (Map<?, ?>) api.get("paths");
    Map.ofEntries(
            Map.entry("/api/v1/posts", List.of("get", "post")),
            Map.entry("/api/v1/reports", List.of("post")),
            Map.entry("/api/v1/me/reports", List.of("get")),
            Map.entry("/api/v1/communities/{community}/moderation/reports", List.of("get")),
            Map.entry(
                "/api/v1/communities/{community}/moderation/reports/{report}", List.of("get")),
            Map.entry(
                "/api/v1/communities/{community}/moderation/reports/{report}/actions",
                List.of("post")),
            Map.entry("/api/v1/admin/moderation/reports", List.of("get")),
            Map.entry("/api/v1/admin/moderation/reports/{report}", List.of("get")),
            Map.entry("/api/v1/admin/moderation/reports/{report}/actions", List.of("post")),
            Map.entry("/api/v1/posts/{id}/comments", List.of("get", "post")),
            Map.entry("/api/v1/comments/{id}", List.of("get", "patch", "delete")),
            Map.entry("/api/v1/comments/{id}/replies", List.of("get", "post")),
            Map.entry("/api/v1/posts/{id}/reactions/me", List.of("put", "delete")),
            Map.entry("/api/v1/comments/{id}/reactions/me", List.of("put", "delete")),
            Map.entry("/api/v1/posts/{id}/reactions", List.of("get")),
            Map.entry("/api/v1/comments/{id}/reactions", List.of("get")),
            Map.entry("/api/v1/posts/{id}", List.of("get", "patch", "delete")),
            Map.entry("/api/v1/posts/{id}/audience", List.of("put")),
            Map.entry("/api/v1/posts/{id}/revisions", List.of("get")),
            Map.entry("/api/v1/posts/{id}/media/{media}/access", List.of("post")),
            Map.entry("/api/v1/media/uploads", List.of("post")),
            Map.entry("/api/v1/media/{id}", List.of("get", "delete")),
            Map.entry("/api/v1/media/{id}/finalize", List.of("post")),
            Map.entry("/api/v1/me/avatar", List.of("put")),
            Map.entry("/api/v1/users/{id}/avatar/access", List.of("post")),
            Map.entry(
                "/api/v1/communities/{community}/moderation/posts/{post}/actions", List.of("post")))
        .forEach(
            (path, methods) -> {
              assertThat(paths.containsKey(path)).describedAs(path).isTrue();
              var item = (Map<?, ?>) paths.get(path);
              for (var method : methods)
                assertThat(item.containsKey(method)).describedAs(path + " " + method).isTrue();
            });
    var operations = new HashSet<String>();
    validate(api, api, operations);
    assertThat(paths.containsKey("/api/v1/comments")).isFalse();
  }

  private void validate(Object node, Map<String, Object> root, Set<String> operations) {
    if (node instanceof Map<?, ?> map) {
      if (map.get("$ref") instanceof String ref) {
        assertThat(ref).startsWith("#/");
        Object target = root;
        for (String component : ref.substring(2).split("/")) {
          assertThat(target).isInstanceOf(Map.class);
          target = ((Map<?, ?>) target).get(component);
          assertThat(target).describedAs(ref).isNotNull();
        }
      }
      if (map.get("operationId") instanceof String id)
        assertThat(operations.add(id)).describedAs(id).isTrue();
      map.values().forEach(value -> validate(value, root, operations));
    } else if (node instanceof List<?> list)
      list.forEach(value -> validate(value, root, operations));
  }
}
