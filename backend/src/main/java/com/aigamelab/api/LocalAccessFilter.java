package com.aigamelab.api;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> LOOPBACK_PEERS = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LOOPBACK_PEERS.contains(request.getRemoteAddr())
                || !LOCAL_HOSTS.contains(request.getServerName().toLowerCase(java.util.Locale.ROOT))
                || !sameOrigin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Local Game Lab access required");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean sameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site)) return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            String scheme = request.getScheme();
            int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
            return scheme.equals(uri.getScheme()) && request.getServerName().equalsIgnoreCase(uri.getHost())
                    && request.getServerPort() == port && uri.getRawUserInfo() == null
                    && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
