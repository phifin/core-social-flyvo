package com.flyvo.socialcore.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
  private final ProblemResponses problems;

  public ProblemDetailsAdvice(ProblemResponses problems) {
    this.problems = problems;
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var problem =
        problems.create(
            servlet(request),
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Request validation failed.");
    List<Violation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new Violation(error.getField(), error.getCode(), "Invalid value."))
            .toList();
    problem.setProperty("violations", violations);
    return new ResponseEntity<>(problem, safeHeaders(headers), HttpStatus.BAD_REQUEST);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var httpStatus = HttpStatus.valueOf(status.value());
    var problem =
        problems.create(
            servlet(request), httpStatus, httpStatus.name(), "The request could not be processed.");
    return new ResponseEntity<>(problem, safeHeaders(headers), status);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
    // Exception messages and stack traces can contain credentials, SQL or private request values.
    LOG.error("Unhandled request failure; exceptionType={}", exception.getClass().getName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .headers(safeHeaders(HttpHeaders.EMPTY))
        .body(
            problems.create(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred."));
  }

  private static HttpServletRequest servlet(WebRequest request) {
    return ((ServletWebRequest) request).getRequest();
  }

  private static HttpHeaders safeHeaders(HttpHeaders original) {
    var headers = new HttpHeaders();
    headers.putAll(original);
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
    headers.setCacheControl("no-store");
    return headers;
  }

  public record Violation(String field, String code, String message) {}
}
