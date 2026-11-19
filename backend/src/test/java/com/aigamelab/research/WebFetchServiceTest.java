package com.aigamelab.research;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.List;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WebFetchServiceTest {
    @Test
    void acceptsHtmlAfterRelativeRedirectAndExtractsText() throws Exception {
        var redirect = new BasicClassicHttpResponse(302);
        redirect.addHeader("Location", "/manual");
        var page = new BasicClassicHttpResponse(200);
        page.setEntity(new StringEntity("<h1>Controls</h1><script>hidden</script>", ContentType.TEXT_HTML));
        var transport = new FixtureClient(redirect, page);
        var fetch = new WebFetchService(transport);
        try {
            assertThat(fetch.fetchText("https://example.test/intro")).isEqualTo("Controls");
            assertThat(transport.paths).containsExactly("/intro", "/manual");
        } finally {
            fetch.close();
        }
    }

    @Test
    void rejectsOversizedUnknownLengthAndDisallowedMedia() throws Exception {
        var oversized = new BasicClassicHttpResponse(200);
        oversized.setEntity(new BasicHttpEntity(new ByteArrayInputStream(new byte[WebFetchService.MAX_BYTES + 1]),
                -1, ContentType.TEXT_HTML, null));
        var nonText = new BasicClassicHttpResponse(200);
        nonText.setEntity(new StringEntity("image", ContentType.APPLICATION_OCTET_STREAM));
        var fetch = new WebFetchService(new FixtureClient(oversized, nonText));
        try {
            assertThat(fetch.fetchHtml("https://example.test/large")).isNull();
            assertThat(fetch.fetchHtml("https://example.test/image")).isNull();
        } finally {
            fetch.close();
        }
    }

    @Test
    void onlyVettedIpv4AddressesArePassedToTransport() throws Exception {
        var external = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        var local = InetAddress.getLoopbackAddress();
        assertThat(WebFetchService.publicAddresses(new InetAddress[]{external})).containsExactly(external);
        assertThatThrownBy(() -> WebFetchService.publicAddresses(new InetAddress[]{local}))
                .isInstanceOf(UnknownHostException.class);
    }

    private static final class FixtureClient extends CloseableHttpClient {
        final List<String> paths = new java.util.ArrayList<>();
        final ArrayDeque<BasicClassicHttpResponse> responses;

        FixtureClient(BasicClassicHttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request, HttpContext context) {
            paths.add(request.getPath());
            return CloseableHttpResponse.adapt(responses.remove());
        }

        @Override public void close(CloseMode mode) {}
        @Override public void close() {}
    }
}
