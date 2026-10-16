package com.flyvo.socialcore.moderation;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModerationDashboardTest {
  @Test
  void staticDashboardIsParseableRestrictedAndUsesOnlyFixedMetricExpressions() throws Exception {
    var dashboard =
        new ObjectMapper().readTree(Files.readString(Path.of("ops/dashboards/phase-7-8.json")));
    assertThat(dashboard.get("panels").size()).isEqualTo(12);
    assertThat(dashboard.get("description").asText())
        .contains("current-ADMIN", "public actuator remains denied");
    var ids = new HashSet<Integer>();
    for (var panel : dashboard.get("panels")) {
      assertThat(ids.add(panel.get("id").asInt())).isTrue();
      assertThat(panel.get("datasource").get("uid").asText()).isEqualTo("${DS_PROMETHEUS}");
      assertThat(panel.get("targets").get(0).get("expr").asText())
          .doesNotContain("user_id", "query_text", "target_id", "Authorization");
    }
  }
}
