package com.flyvo.socialcore.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsAdviceTest {
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var converter = new JacksonJsonHttpMessageConverter();
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setMessageConverters(converter)
            .setControllerAdvice(
                new ProblemDetailsAdvice(new ProblemResponses(converter.getMapper())))
            .addFilters(new RequestIdFilter())
            .build();
  }

  @Test
  void validationDoesNotEchoRejectedValues() throws Exception {
    mvc.perform(
            post("/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")
                .header("X-Request-ID", "validation-1"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.requestId").value("validation-1"))
        .andExpect(jsonPath("$.violations[0].field").value("name"))
        .andExpect(jsonPath("$.violations[0].rejectedValue").doesNotExist());
  }

  @Test
  void malformedJsonDoesNotLeakParserDetails() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("private-secret"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-secret"))));
  }

  @Test
  void unexpectedExceptionIsSanitized() throws Exception {
    mvc.perform(get("/probe").header("X-Request-ID", "error-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.requestId").value("error-1"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-secret"))));
  }

  // Test-only controller; no probe endpoint ships in the application.
  @RestController
  static class ProbeController {
    @PostMapping("/probe")
    void validate(@Valid @RequestBody Input input) {}

    @GetMapping("/probe")
    void fail() {
      throw new IllegalStateException("private-secret");
    }
  }

  record Input(@NotBlank String name) {}
}
