package com.flyvo.socialcore.moderation.application.port;

import com.flyvo.socialcore.moderation.domain.Report;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReportRepository {
  Report find(UUID id, boolean lock);

  Report insertOrUnresolved(Report report);

  void save(Report report, Instant now);

  List<Report> page(UUID reporter, UUID community, int size, int offset);
}
