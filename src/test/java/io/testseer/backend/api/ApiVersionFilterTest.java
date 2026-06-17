package io.testseer.backend.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVersionFilterTest {

    private final ApiVersionFilter filter = new ApiVersionFilter(List.of(1));

    @Test
    void defaultsToVersion1_whenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/facts/class");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(ApiVersionFilter.HEADER)).isEqualTo("1");
    }

    @Test
    void rejectsUnsupportedVersion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/facts/class");
        request.addHeader(ApiVersionFilter.HEADER, "99");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void skipsWebhookPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook/github");
        request.addHeader(ApiVersionFilter.HEADER, "99");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
