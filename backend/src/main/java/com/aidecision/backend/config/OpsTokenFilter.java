package com.aidecision.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Optional Bearer-token gate matching the frontend VITE_OPS_TOKEN.
 * If app.ops-token is blank, all requests pass through (local dev).
 */
@Component
@Order(1)
public class OpsTokenFilter implements Filter {

    private final String opsToken;
    private final ObjectMapper mapper;

    public OpsTokenFilter(
            @Value("${app.ops-token:}") String opsToken,
            ObjectMapper mapper) {
        this.opsToken = opsToken == null ? "" : opsToken.trim();
        this.mapper = mapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (opsToken.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;

        if ("OPTIONS".equalsIgnoreCase(req.getMethod()) ||
                "/health".equals(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String auth = req.getHeader("Authorization");
        String token = null;
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = auth.substring(7).trim();
        }

        if (!opsToken.equals(token)) {
            HttpServletResponse res = (HttpServletResponse) response;
            res.setStatus(401);
            res.setContentType("application/json");
            mapper.writeValue(res.getOutputStream(),
                    Map.of("ok", false, "message", "Unauthorized: invalid ops token"));
            return;
        }

        chain.doFilter(request, response);
    }
}
