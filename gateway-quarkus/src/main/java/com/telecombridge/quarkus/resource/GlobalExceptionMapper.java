package com.telecombridge.quarkus.resource;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global JAX-RS exception mapper for validation and unexpected errors.
 *
 * <p>Maps {@link ConstraintViolationException} (bean validation failures)
 * to HTTP 400 with a structured JSON error body, mirroring the Spring Boot
 * {@code GlobalExceptionHandler} behaviour.
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        if (exception instanceof ConstraintViolationException cve) {
            List<String> violations = cve.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toList());

            log.warn("Validation failure: {}", violations);

            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                    "error", "Validation failed",
                    "violations", violations))
                .build();
        }

        log.error("Unhandled exception: {}", exception.getMessage(), exception);
        return Response.serverError()
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", "Internal server error: " + exception.getMessage()))
            .build();
    }
}
