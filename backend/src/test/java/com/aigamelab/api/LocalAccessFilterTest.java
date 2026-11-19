package com.aigamelab.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAccessFilterTest {
    @Test
    void sameOriginPageAndLocalCliCanUseBackend() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/config/proposals/1/approve");
        request.setServerPort(5173);
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(status(request)).isEqualTo(200);
        assertThat(status(new MockHttpServletRequest("GET", "/sse/global"))).isEqualTo(200);
    }

    @Test
    void sourceReadsAndApprovalsShareLocalBoundary() throws Exception {
        var remote = new MockHttpServletRequest("GET", "/api/local-tests/runs/run/source");
        remote.setRemoteAddr("192.0.2.10");
        assertThat(status(remote)).isEqualTo(403);

        var foreignOrigin = new MockHttpServletRequest("POST", "/api/config/proposals/1/approve");
        foreignOrigin.addHeader("Origin", "http://localhost:49152");
        assertThat(status(foreignOrigin)).isEqualTo(403);

        var foreignHost = new MockHttpServletRequest("GET", "/sse/global");
        foreignHost.setServerName("example.test");
        assertThat(status(foreignHost)).isEqualTo(403);
    }

    private int status(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new LocalAccessFilter().doFilter(request, response, chain);
        assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
        return response.getStatus();
    }
}
