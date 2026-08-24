package com.braintwinx.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a trace ID to every request (project brief section 28).
 *
 * <p>The ID is placed in the SLF4J {@link MDC} so it appears on every log line for the request,
 * returned in the {@code X-Trace-Id} response header, and stored on audit rows. That gives one
 * handle linking a client-visible error to the full server-side detail — which is what allows the
 * error envelope to disclose nothing else.
 *
 * <p>An inbound {@code X-Trace-Id} is honoured so a trace can span the frontend and backend, but
 * it is <strong>validated and length-capped first</strong>. A client-supplied value reaches log
 * files and a database column, so accepting it verbatim would allow log injection (CRLF forging
 * fake log lines) and oversized writes. Anything not matching a conservative pattern is replaced
 * with a fresh generated ID rather than sanitised, because a caller sending a malformed trace ID
 * has no legitimate expectation about it.
 *
 * <p>Ordered first so that even a request rejected by authentication is traceable.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Runs before Spring Security so unauthenticated failures are still correlated. */
    public static final int ORDER = Integer.MIN_VALUE + 10;

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final int MAX_LENGTH = 64;

    /** Conservative allow-list: hex, dashes, and underscores only. No CR, LF, or spaces. */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always cleared: thread pools reuse threads, and a leaked MDC value would
            // mislabel a later, unrelated request.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * @param inbound a client-supplied trace ID, possibly null or hostile
     * @return the inbound value if it is safe and well-formed, otherwise a fresh generated ID
     */
    private String resolveTraceId(String inbound) {
        if (inbound != null
                && inbound.length() <= MAX_LENGTH
                && SAFE_TRACE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** @return the current request's trace ID, or {@code "unknown"} outside a request */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_KEY);
        return traceId != null ? traceId : "unknown";
    }
}
