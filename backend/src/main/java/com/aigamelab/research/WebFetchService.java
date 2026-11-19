package com.aigamelab.research;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebFetchService {
    private static final Logger log = LoggerFactory.getLogger(WebFetchService.class);
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int TIMEOUT_SECONDS = 10;
    private static final Set<String> TEXT_TYPES = Set.of("text/html", "application/xhtml+xml", "text/plain");

    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "research-fetch-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public WebFetchService() {
        this(new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return publicAddresses(super.resolve(host));
            }
        });
    }

    WebFetchService(org.apache.hc.client5.http.DnsResolver resolver) {
        this(createClient(resolver));
    }

    WebFetchService(CloseableHttpClient client) {
        this.client = client;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    private static CloseableHttpClient createClient(org.apache.hc.client5.http.DnsResolver resolver) {
        var timeout = Timeout.ofSeconds(TIMEOUT_SECONDS);
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout).setResponseTimeout(timeout)
                        .setHardCancellationEnabled(true).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().build();
    }

    static InetAddress[] publicAddresses(InetAddress[] addresses) throws UnknownHostException {
        // Supply the vetted addresses directly to the connection manager. IPv6 is disabled
        // for this release so translated and special-use IPv6 routes are outside this policy.
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address && !isPublicIpv4(address.getAddress())) {
                throw new UnknownHostException("Research destination is not public IPv4");
            }
        }
        var publicIpv4 = java.util.Arrays.stream(addresses).filter(Inet4Address.class::isInstance)
                .toArray(InetAddress[]::new);
        if (publicIpv4.length == 0) throw new UnknownHostException("Research requires public IPv4");
        return publicIpv4;
    }

    private static boolean isPublicIpv4(byte[] address) {
        int a = Byte.toUnsignedInt(address[0]);
        int b = Byte.toUnsignedInt(address[1]);
        int c = Byte.toUnsignedInt(address[2]);
        return a > 0 && a < 224 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
                && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                && !(a == 203 && b == 0 && c == 113);
    }

    public String fetchText(String url) {
        String html = fetchHtml(url);
        if (html == null) return null;
        var document = Jsoup.parse(html);
        document.select("script, style, noscript, nav, footer, header").remove();
        String text = document.body().text();
        return text.length() > 8000 ? text.substring(0, 8000) + "\n[...截断]" : text;
    }

    public String fetchHtml(String url) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try {
            URI uri = URI.create(url);
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validateUri(uri);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Research fetch timed out");
                var request = new HttpGet(uri);
                request.setHeader("User-Agent", "AIGameLab/1.0 Research Bot");
                request.setHeader("Accept", "text/html,application/xhtml+xml,text/plain");
                request.setHeader("Accept-Encoding", "identity");
                var timeout = deadlines.schedule(request::cancel, remaining, TimeUnit.NANOSECONDS);
                try (var response = client.executeOpen(null, request, null)) {
                    try {
                        int status = response.getCode();
                        if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                            var location = response.getFirstHeader("Location");
                            if (location == null || redirects == MAX_REDIRECTS) throw new IOException("Invalid redirect");
                            uri = uri.resolve(location.getValue());
                            continue;
                        }
                        var entity = response.getEntity();
                        if (status < 200 || status >= 300 || entity == null || entity.getContentType() == null) {
                            throw new IOException("No usable page response");
                        }
                        var type = ContentType.parse(entity.getContentType());
                        String encoding = entity.getContentEncoding();
                        if (!TEXT_TYPES.contains(type.getMimeType().toLowerCase(Locale.ROOT))
                                || encoding != null && !"identity".equalsIgnoreCase(encoding)
                                || entity.getContentLength() > MAX_BYTES) {
                            throw new IOException("Unsupported page type, encoding or size");
                        }
                        byte[] body = entity.getContent().readNBytes(MAX_BYTES + 1);
                        if (body.length > MAX_BYTES || System.nanoTime() >= deadline) {
                            throw new IOException("Page exceeded the fetch limit");
                        }
                        var charset = type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
                        return new String(body, charset);
                    } finally {
                        // Close without draining unread redirect or rejected response bodies.
                        request.cancel();
                    }
                } finally {
                    timeout.cancel(false);
                }
            }
        } catch (Exception ex) {
            log.debug("Research fetch rejected or failed: {}", ex.getClass().getSimpleName());
        }
        return null;
    }

    private static void validateUri(URI uri) {
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort();
        if ((!http && !https) || uri.getHost() == null || uri.getHost().contains(":")
                || uri.getHost().matches("[0-9.]+") || uri.getUserInfo() != null
                || port != -1 && port != (https ? 443 : 80)) {
            throw new IllegalArgumentException("Research requires a public HTTP(S) page on its default port");
        }
    }

    @PreDestroy
    void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
