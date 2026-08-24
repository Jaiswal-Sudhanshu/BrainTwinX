package com.braintwinx.exception;

import com.braintwinx.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Single exit point for every error the API returns (project brief section 19).
 *
 * <p>Two rules govern everything here.
 *
 * <ol>
 *   <li><strong>The client learns nothing beyond a code, a generic message, and a traceId.</strong>
 *       No stack trace, no exception class, no SQL, no constraint name, no file path. Full detail
 *       goes to the server log under the same traceId.</li>
 *   <li><strong>Unrecognised exceptions become {@code INTERNAL_ERROR}.</strong> The default is
 *       to disclose nothing, so a new exception type introduced later cannot accidentally leak
 *       internals by being unhandled.</li>
 * </ol>
 *
 * <p>Log levels are chosen so that ordinary client mistakes do not create noise that masks real
 * faults: client errors are logged at WARN with a one-line summary, server faults at ERROR with
 * the stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------
    // Domain exceptions — the code is carried on the exception itself.
    // ------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ApiErrorCode code = ex.getErrorCode();
        String traceId = CorrelationIdFilter.currentTraceId();

        if (code.status().is5xxServerError()) {
            log.error("{} at {} {}: {}", code, request.getMethod(), request.getRequestURI(),
                    ex.getMessage(), ex);
        } else {
            // The internal message is logged; only the generic message reaches the client.
            log.warn("{} at {} {}: {}", code, request.getMethod(), request.getRequestURI(),
                    ex.getMessage());
        }
        return ResponseEntity.status(code.status()).body(ApiError.of(code, traceId));
    }

    // ------------------------------------------------------------------
    // Security
    // ------------------------------------------------------------------

    /**
     * Authentication failure.
     *
     * <p>Always the generic {@code UNAUTHORIZED} response. Spring may raise a specific subtype
     * (bad credentials, unknown user, disabled account) but the distinction is never surfaced,
     * because doing so would make the endpoint a user-enumeration and account-state oracle.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                        HttpServletRequest request) {
        String traceId = CorrelationIdFilter.currentTraceId();
        log.warn("Authentication failed at {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(ApiErrorCode.UNAUTHORIZED, traceId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                      HttpServletRequest request) {
        String traceId = CorrelationIdFilter.currentTraceId();
        log.warn("Access denied at {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(ApiErrorCode.FORBIDDEN, traceId));
    }

    // ------------------------------------------------------------------
    // Request validation — the one case where field detail IS returned,
    // because the caller cannot fix the request without knowing what failed.
    // ------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                // Sorted so responses are deterministic and easy to assert on.
                .sorted(Comparator.comparing(ApiError.FieldError::field))
                .toList();

        log.warn("Validation failed on {} field(s)", fieldErrors.size());
        return ResponseEntity.badRequest()
                .body(ApiError.validation(CorrelationIdFilter.currentTraceId(), fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleHandlerValidation(HandlerMethodValidationException ex) {
        log.warn("Parameter validation failed: {} violation(s)", ex.getAllErrors().size());
        return badRequest(ApiErrorCode.INVALID_REQUEST);
    }

    /**
     * Malformed or unexpected request body.
     *
     * <p>Deliberately does not echo the parse error: Jackson messages can quote the offending
     * payload, which may contain patient data or attacker-controlled content.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getClass().getSimpleName());
        return badRequest(ApiErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return badRequest(ApiErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeded the permitted size");
        return status(ApiErrorCode.FILE_TOO_LARGE);
    }

    // ------------------------------------------------------------------
    // Persistence and concurrency
    // ------------------------------------------------------------------

    /**
     * A database constraint rejected the write.
     *
     * <p>The constraint name is logged but never returned: it would disclose schema internals.
     * Reaching here generally means a boundary check is missing, so it is logged at WARN with
     * enough detail to add one.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    HttpServletRequest request) {
        log.warn("Data integrity violation at {} {}: {}", request.getMethod(),
                request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return status(ApiErrorCode.DUPLICATE_RESOURCE);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Concurrent modification detected: {}", ex.getMessage());
        return status(ApiErrorCode.CONCURRENT_MODIFICATION);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(InvalidStateTransitionException ex) {
        log.warn("Illegal {} transition {} -> {}", ex.getEntityType(), ex.getFromState(),
                ex.getToState());
        return status(ApiErrorCode.INVALID_STATE_TRANSITION);
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    @ExceptionHandler({NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiError> handleNoHandler(Exception ex) {
        return status(ApiErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // Catch-all — must stay last and must disclose nothing.
    // ------------------------------------------------------------------

    /**
     * Anything not handled above.
     *
     * <p>Logged at ERROR with the full stack trace, and answered with a bare
     * {@code INTERNAL_ERROR} plus the traceId. This is the safety net that makes it impossible
     * for a newly introduced exception type to leak internals by being unhandled.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = CorrelationIdFilter.currentTraceId();
        log.error("Unhandled exception at {} {} [traceId={}]", request.getMethod(),
                request.getRequestURI(), traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ApiErrorCode.INTERNAL_ERROR, traceId));
    }

    // ------------------------------------------------------------------

    private ResponseEntity<ApiError> badRequest(ApiErrorCode code) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(code, CorrelationIdFilter.currentTraceId()));
    }

    private ResponseEntity<ApiError> status(ApiErrorCode code) {
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, CorrelationIdFilter.currentTraceId()));
    }
}
