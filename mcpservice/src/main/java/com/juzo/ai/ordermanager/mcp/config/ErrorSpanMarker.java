package com.juzo.ai.ordermanager.mcp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Puts server-side failures on the request span. Spring only records an exception on the HTTP span when
 * it escapes the dispatcher; a 5xx produced by a ResponseStatusException or an @ExceptionHandler would
 * leave the span looking healthy. This resolver runs first, only observes, and returns null so the
 * normal handling (status, body) is unchanged.
 *
 * Log rule: a handled 5xx is an ERROR (an engineer must look). Unhandled exceptions are already logged
 * by the servlet container, so they are not logged again here.
 */
@Component
class ErrorSpanMarker implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ErrorSpanMarker.class);

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            return null;   // 401/403: client problem
        }
        boolean handled5xx = false;
        if (ex instanceof ErrorResponse er) {
            if (!er.getStatusCode().is5xxServerError()) {
                return null;   // 4xx: expected outcome, not a span error
            }
            handled5xx = true;
        }
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(ctx -> ctx.setError(ex));
        if (handled5xx) {
            log.error("Request failed with a 5xx response: {}", request.getRequestURI(), ex);
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
