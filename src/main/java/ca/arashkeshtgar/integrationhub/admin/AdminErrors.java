package ca.arashkeshtgar.integrationhub.admin;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin API answers a refusal as {@code {"error": "..."}}, the same shape as the access filter.
 * The reasons are fixed phrases written in this package, so they are safe to show; Spring's own
 * error body leaves the message out.
 */
@RestControllerAdvice(assignableTypes = {AdminController.class, MessagesController.class})
class AdminErrors {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> refused(ResponseStatusException e) {
    HttpStatusCode status = e.getStatusCode();
    String reason =
        e.getReason() != null
            ? e.getReason()
            : status instanceof HttpStatus known ? known.getReasonPhrase() : "Refused.";
    return ResponseEntity.status(status).body(Map.of("error", reason));
  }
}
