/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.web;

import dev.twitterclone.user.persistence.HandleAlreadyTakenException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the exceptions this service throws into RFC 9457 problem responses.
 *
 * <p>Centralised so that no controller has to decide a status code, and so the shape of an error is
 * one file rather than a convention. Every branch here is deliberate about how much it says:
 * validation failures name the field because the caller can fix it, and everything else does not,
 * because a stack trace or a DynamoDB message in a response body is reconnaissance.
 */
@RestControllerAdvice
public class ErrorHandler {

  /**
   * A handle somebody else already has.
   *
   * @param e the failure
   * @return 409
   */
  @ExceptionHandler(HandleAlreadyTakenException.class)
  public ProblemDetail handleTaken(HandleAlreadyTakenException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setTitle("Handle already taken");
    problem.setDetail(e.getMessage());
    return problem;
  }

  /**
   * A body that failed bean validation.
   *
   * @param e the failure
   * @return 400 with a field-to-message map
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleInvalid(MethodArgumentNotValidException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setTitle("Invalid request");
    Map<String, String> errors =
        e.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(),
                    // A field with two failing constraints would otherwise throw here and
                    // turn a 400 into a 500.
                    (first, second) -> first));
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * An argument the domain rejected.
   *
   * @param e the failure
   * @return 400
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setTitle("Invalid request");
    problem.setDetail(e.getMessage());
    return problem;
  }

  /**
   * Something that does not exist.
   *
   * @return 404
   */
  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail handleNotFound() {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    return problem;
  }

  /**
   * A token that is well-formed but no longer usable.
   *
   * @return 401
   */
  @ExceptionHandler(UnauthenticatedException.class)
  public ProblemDetail handleUnauthenticated() {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setTitle("Unauthenticated");
    return problem;
  }
}
