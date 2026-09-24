/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures onto RFC 9457 problem documents.
 *
 * <p>Notably short, because the read path has almost nothing to refuse: an unreachable dependency
 * degrades into a smaller timeline rather than an error, which is handled in the clients and never
 * reaches here.
 */
@RestControllerAdvice
public class ErrorHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }
}
