package com.flyvo.fixtures.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

// Outside the application scan: used only to prove schema validation refuses absent tables.
@Entity
@Table(name = "missing_table_probe", schema = "platform")
public class MissingTableProbe {
  @Id private UUID id;
}
