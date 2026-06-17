package io.testseer.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.observability.MdcContext;
import io.testseer.backend.observability.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiVersionFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-TestSeer-Api-Version";

    private final List<Integer> supportedVersions;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiVersionFilter(
            @Value("${testseer.api.supported-versions:1}") List<Integer> supportedVersions) {
        this.supportedVersions = supportedVersions != null && !supportedVersions.isEmpty()
                ? supportedVersions : List.of(1);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/webhook/")
                || path.startsWith("/actuator/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int version = parseVersion(request.getHeader(HEADER));
        if (!supportedVersions.contains(version)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), new ApiErrorBody(
                    "BAD_REQUEST",
                    "Unsupported API version: " + version,
                    "Supported versions: " + supportedVersions,
                    UUID.randomUUID().toString()
            ));
            return;
        }
        MdcContext.put(MdcKeys.API_VERSION, String.valueOf(version));
        response.setHeader(HEADER, String.valueOf(version));
        try {
            chain.doFilter(request, response);
        } finally {
            MdcContext.remove(MdcKeys.API_VERSION);
        }
    }

    private static int parseVersion(String header) {
        if (header == null || header.isBlank()) return 1;
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private record ApiErrorBody(String error, String message, String hint, String requestId) {}
}
