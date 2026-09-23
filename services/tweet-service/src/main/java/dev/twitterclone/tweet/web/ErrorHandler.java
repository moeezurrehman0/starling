/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.web;

import dev.twitterclone.tweet.persistence.TweetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures onto RFC 9457 problem documents.
 *
 * <p>One place, so that every error has the same shape. Without it each handler invents its own
 * body and clients end up parsing three different error formats from one service.
 */
@RestControllerAdvice
public class ErrorHandler {

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
  }

  /**
   * A tweet exists but belongs to somebody else.
   *
   * <p>403 rather than 404. The caller already learned the tweet exists by any other read, so
   * hiding it here buys nothing and costs the client a useful distinction.
   */
  @ExceptionHandler(TweetRepository.NotTheAuthorException.class)
  ProblemDetail notTheAuthor(TweetRepository.NotTheAuthorException e) {
    return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
    return problem(HttpStatus.CONFLICT, "Idempotency key conflict", e.getMessage());
  }

  /**
   * Domain rule violations: text too long, too many images, a media key that is not the caller's.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalidBody(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Request body failed validation");
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setTitle(title);
    problem.setDetail(detail);
    return problem;
  }
}
