package com.aidecision.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OpsTokenFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emptyTokenPassesThrough() throws ServletException, IOException {
        OpsTokenFilter f = new OpsTokenFilter("", mapper);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/rag/ingest");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void rejectsWrongBearer() throws ServletException, IOException {
        OpsTokenFilter f = new OpsTokenFilter("secret", mapper);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/rag/ingest");
        req.addHeader("Authorization", "Bearer wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsMatchingBearer() throws ServletException, IOException {
        OpsTokenFilter f = new OpsTokenFilter("secret", mapper);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/rag/ingest");
        req.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void optionsBypassesAuth() throws ServletException, IOException {
        OpsTokenFilter f = new OpsTokenFilter("secret", mapper);
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/rag/ingest");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void healthBypassesAuth() throws ServletException, IOException {
        OpsTokenFilter f = new OpsTokenFilter("secret", mapper);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }
}
