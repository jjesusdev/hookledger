package com.jjesusdev.hookledger.web;

import com.jjesusdev.hookledger.ingest.WebhookRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WebhookRejectedException.class)
    public ResponseEntity<ProblemDetail> handleWebhookRejected(WebhookRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(e.status()),
                e.getMessage()
        );
        problem.setTitle(titleFor(e.status()));
        problem.setProperty("code", e.code());
        e.properties().forEach(problem::setProperty);
        return ResponseEntity.status(e.status()).body(problem);
    }

    @ExceptionHandler({
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception e) {
        return problem(400, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException e) {
        return problem(404, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(500, "INTERNAL_ERROR", "Unexpected error");
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(status),
                detail
        );
        problem.setTitle(titleFor(status));
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    private String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            default -> "Internal Server Error";
        };
    }
}
