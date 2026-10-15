package com.melody.minirag.web;

import com.melody.minirag.config.MiniRagProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DemoKeyFilterTest {

    private final MiniRagProperties props =
            new MiniRagProperties(500, 80, null, null, "secret123", "admin456");

    @Test
    void missingKeyReturns401() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(props);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/chat"), resp, new MockFilterChain());
        assertEquals(401, resp.getStatus());
    }

    @Test
    void validKeyInHeaderOrParamPasses() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(props);

        MockHttpServletRequest withHeader = new MockHttpServletRequest("POST", "/api/chat");
        withHeader.addHeader("X-Demo-Key", "secret123");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(withHeader, resp1, new MockFilterChain());
        assertEquals(200, resp1.getStatus());

        MockHttpServletRequest withParam = new MockHttpServletRequest("GET", "/api/search");
        withParam.addParameter("key", "secret123");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(withParam, resp2, new MockFilterChain());
        assertEquals(200, resp2.getStatus());
    }

    @Test
    void exceedingPerMinuteLimitReturns429() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(props);
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/chat");
            req.addHeader("X-Demo-Key", "secret123");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, new MockFilterChain());
            assertEquals(200, resp.getStatus(), "第 " + (i + 1) + " 次应放行");
        }
        MockHttpServletRequest burst = new MockHttpServletRequest("POST", "/api/chat");
        burst.addHeader("X-Demo-Key", "secret123");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(burst, resp, new MockFilterChain());
        assertEquals(429, resp.getStatus());
    }

    @Test
    void ingestRejectedWithVisitorKey() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/documents");
        req.addHeader("X-Demo-Key", "secret123");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        assertEquals(401, resp.getStatus());
    }

    @Test
    void ingestAllowedWithAdminKey() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(props);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/documents");
        req.addHeader("X-Admin-Key", "admin456");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void allRequestsPassWhenNoKeysConfigured() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(new MiniRagProperties(500, 80, null, null, "", ""));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/chat"), resp, new MockFilterChain());
        assertEquals(200, resp.getStatus());

        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/documents"), resp2, new MockFilterChain());
        assertEquals(200, resp2.getStatus());
    }

    @Test
    void unprotectedPathsNotFiltered() throws ServletException, IOException {
        DemoKeyFilter filter = new DemoKeyFilter(props);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/"), resp, new MockFilterChain());
        assertEquals(200, resp.getStatus());
    }
}
