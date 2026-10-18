package com.flyvo.socialcore.account.web;

import com.flyvo.socialcore.account.api.AccountErasure;
import com.flyvo.socialcore.platform.api.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ErasureController {
  private final AccountErasure erasure;

  ErasureController(AccountErasure erasure) {
    this.erasure = erasure;
  }

  record Resume(AccountErasure.Step step) {}

  @GetMapping("/api/v1/admin/accounts/{userId}/erasure")
  ResponseEntity<AccountErasure.Progress> progress(
      @AuthenticationPrincipal ActorContext actor, @PathVariable UUID userId) {
    return response(erasure.progress(actor, userId));
  }

  @PostMapping("/api/v1/admin/accounts/{userId}/erasure/resumes")
  ResponseEntity<AccountErasure.Progress> resume(
      @AuthenticationPrincipal ActorContext actor,
      @PathVariable UUID userId,
      @RequestHeader(name = "If-Match", required = false) String match,
      @RequestBody Resume input) {
    if (input == null) throw Failure.invalid();
    return response(erasure.resume(actor, userId, ResourceVersions.require(match), input.step()));
  }

  private ResponseEntity<AccountErasure.Progress> response(AccountErasure.Progress result) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .eTag("\"" + result.version() + "\"")
        .body(result);
  }
}
