package io.testseer.backend.observability;

import io.micrometer.core.instrument.Timer;
import io.testseer.backend.config.ObservabilityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@ConditionalOnBean(TestSeerMetrics.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private final ObservabilityProperties properties;
    private final TestSeerMetrics metrics;

    public RequestIdFilter(ObservabilityProperties properties, TestSeerMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(properties.requestIdHeader());
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MdcContext.put(MdcKeys.REQUEST_ID, requestId);

        String deliveryId = request.getHeader("X-GitHub-Delivery");
        MdcContext.put(MdcKeys.GITHUB_DELIVERY, deliveryId);

        String serviceId = request.getParameter("serviceId");
        MdcContext.put(MdcKeys.SERVICE_ID, serviceId);

        String mcpTool = request.getHeader(properties.mcpToolHeader());
        MdcContext.put(MdcKeys.TOOL_NAME, mcpTool);

        response.setHeader(properties.requestIdHeader(), requestId);

        String endpoint = normalizeEndpoint(request);
        Timer.Sample sample = metrics.startQueryTimer();
        try {
            filterChain.doFilter(request, response);
            recordMcpRequest(request, mcpTool, response.getStatus());
        } finally {
            metrics.recordQueryDuration(sample, endpoint);
            MdcContext.clear();
        }
    }

    private void recordMcpRequest(HttpServletRequest request, String mcpTool, int status) {
        String client = request.getHeader(properties.mcpClientHeader());
        if (client != null && client.startsWith("testseer-mcp")) {
            metrics.recordMcpRequest(mcpTool != null ? mcpTool : "unknown", status);
        }
    }

    static String normalizeEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/v1/facts/")) return "/v1/facts/*";
        if (path.startsWith("/v1/graph/")) return "/v1/graph/*";
        if (path.startsWith("/v1/status")) return "/v1/status/*";
        if (path.startsWith("/v1/jobs/")) return "/v1/jobs/*";
        if (path.startsWith("/admin/index/")) return "/admin/index/*";
        if (path.startsWith("/registry/")) return "/registry/*";
        if (path.startsWith("/webhook/")) return "/webhook/*";
        if (path.startsWith("/actuator/")) return "/actuator/*";
        return path;
    }
}
