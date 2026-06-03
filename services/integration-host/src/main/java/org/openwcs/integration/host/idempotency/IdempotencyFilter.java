package org.openwcs.integration.host.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes host POSTs idempotent: a client may send an {@code Idempotency-Key} header, and a
 * repeat of the same key replays the original response instead of re-processing (so a retry
 * never double-creates an order/ASN/adjustment). Only successful (2xx) responses are recorded.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";

    private final IdempotencyRepository store;

    public IdempotencyFilter(IdempotencyRepository store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()
                || !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().startsWith("/api/host")) {
            chain.doFilter(request, response);
            return;
        }

        Optional<IdempotencyRecord> existing = store.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            response.setStatus(record.getHttpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            if (record.getResponseBody() != null) {
                response.getWriter().write(record.getResponseBody());
            }
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        int status = wrapper.getStatus();
        String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        wrapper.copyBodyToResponse();

        if (status >= 200 && status < 300) {
            try {
                store.save(new IdempotencyRecord(key, status, body));
            } catch (DataIntegrityViolationException race) {
                // A concurrent request with the same key already stored it; nothing to do.
            }
        }
    }
}
