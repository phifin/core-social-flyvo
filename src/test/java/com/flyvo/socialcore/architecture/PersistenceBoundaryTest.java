package com.flyvo.socialcore.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PersistenceBoundaryTest {
  private static final Pattern MIGRATION = Pattern.compile("V(\\d{4})__([a-z]+)_[a-z0-9_]+\\.sql");
  private static final Pattern TABLE_REFERENCE =
      Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+([a-z_]+)\\.([a-z_][a-z_0-9]*)");
  private static final Set<String> PUBLISHED_VIEWS =
      Set.of(
          "account_api.active_users_v1",
          "graph_api.follows_v1",
          "graph_api.friendships_v1",
          "graph_api.blocks_v1",
          "graph_api.mutes_v1",
          "community_api.communities_v1",
          "community_api.memberships_v1");

  @Test
  void migrationsHaveAnOwnerAndUniqueGlobalVersion() throws IOException {
    var versions = new HashSet<String>();
    try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
      var migrations = files.filter(path -> path.toString().endsWith(".sql")).toList();
      assertThat(migrations).isNotEmpty();
      for (var path : migrations) {
        var matcher = MIGRATION.matcher(path.getFileName().toString());
        assertThat(matcher.matches()).as("migration name: %s", path).isTrue();
        assertThat(versions.add(matcher.group(1))).as("unique migration version").isTrue();
        assertThat(ModuleRules.DEPENDENCIES).containsKey(matcher.group(2));
      }
    }
  }

  @Test
  void runtimeQueriesCannotReachPrivateForeignTables() throws IOException {
    var root = Path.of("src/main/java/com/flyvo/socialcore");
    try (var files = Files.walk(root)) {
      for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String path = root.relativize(file).toString().replace('\\', '/');
        String owner = path.split("/")[0];
        var matcher = TABLE_REFERENCE.matcher(Files.readString(file));
        while (matcher.find()) {
          assertThat(allowed(owner, path, matcher.group(1), matcher.group(2)))
              .as("SQL boundary: %s references %s.%s", file, matcher.group(1), matcher.group(2))
              .isTrue();
        }
      }
    }
  }

  @Test
  void queryGuardRejectsForeignBaseTablesAndUnpublishedViews() {
    assertThat(
            allowed(
                "content", "content/infrastructure/persistence/Queries.java", "graph", "follow"))
        .isFalse();
    assertThat(
            allowed(
                "content",
                "content/infrastructure/persistence/Queries.java",
                "graph_api",
                "follows_v1"))
        .isTrue();
    assertThat(allowed("feed", "feed/infrastructure/Queries.java", "graph_api", "follows_v1"))
        .isFalse();
    assertThat(
            allowed(
                "content",
                "content/infrastructure/persistence/Queries.java",
                "graph_api",
                "secret_v1"))
        .isFalse();
  }

  private static boolean allowed(String owner, String path, String schema, String table) {
    if (schema.equals(owner)) return true;
    // Metadata/system schemas are not business-module back doors.
    if (Set.of("information_schema", "pg_catalog").contains(schema)) return true;
    return owner.equals("content")
        && path.startsWith("content/infrastructure/persistence/")
        && PUBLISHED_VIEWS.contains(schema + "." + table);
  }
}
