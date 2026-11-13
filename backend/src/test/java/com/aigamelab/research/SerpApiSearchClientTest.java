package com.aigamelab.research;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * TH 验收：SerpApiSearchClient WireMock 映射。
 *
 * <p>标准响应正确映射为 SearchResult（title/url/snippet）；
 * HTTP 4xx/5xx 抛 SearchGatewayException；请求形状（q/num/api_key query 参数）一并断言。
 */
@DisplayName("TH SerpApiSearchClient WireMock 映射")
class SerpApiSearchClientTest {

    private WireMockServer wire;
    private SerpApiSearchClient client;

    @BeforeEach
    void setUp() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        client = new SerpApiSearchClient("test-key-abc", wire.baseUrl(), 5);
    }

    @AfterEach
    void tearDown() {
        wire.stop();
    }

    @Test
    void mapsOrganicResults() {
        wire.stubFor(get(urlEqualTo("/search.json?engine=google&q=AI%20%E6%B8%B8%E6%88%8F&num=5&api_key=test-key-abc"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "search_information": {"total_results": 1234},
                                  "organic_results": [
                                    {"title": "AI 游戏设计指南", "link": "https://a.example.com/1",
                                     "snippet": "AI 用于游戏设计的实践指南"},
                                    {"title": "游戏 AI 综述", "link": "https://b.example.com/2",
                                     "snippet": "综述游戏 AI 技术"}
                                  ]
                                }
                                """)));

        List<SearchGateway.SearchResult> results = client.search("AI 游戏", 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("AI 游戏设计指南");
        assertThat(results.get(0).url()).isEqualTo("https://a.example.com/1");
        assertThat(results.get(0).snippet()).isEqualTo("AI 用于游戏设计的实践指南");
        assertThat(results.get(1).title()).isEqualTo("游戏 AI 综述");

        // 请求形状：query + num + api_key（密钥走 query 参数，不硬编码）
        wire.verify(1, getRequestedFor(
                urlEqualTo("/search.json?engine=google&q=AI%20%E6%B8%B8%E6%88%8F&num=5&api_key=test-key-abc")));
    }

    @Test
    void capsNumResultsAt100() {
        wire.stubFor(get(urlEqualTo("/search.json?engine=google&q=test&num=100&api_key=test-key-abc"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"organic_results": [
                                  {"title": "t", "link": "https://x.example.com", "snippet": "s"}
                                ]}
                                """)));

        client.search("test", 500); // 超过上限 → 100

        wire.verify(1, getRequestedFor(
                urlEqualTo("/search.json?engine=google&q=test&num=100&api_key=test-key-abc")));
    }

    @Test
    void http401ThrowsSearchGatewayException() {
        wire.stubFor(get(urlEqualTo("/search.json?engine=google&q=test&num=5&api_key=test-key-abc"))
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"error\":\"Invalid API key\"}")));

        assertThatThrownBy(() -> client.search("test", 5))
                .isInstanceOf(SerpApiSearchClient.SearchGatewayException.class)
                .hasMessageContaining("401");
    }

    @Test
    void emptyOrganicResultsThrows() {
        wire.stubFor(get(urlEqualTo("/search.json?engine=google&q=test&num=5&api_key=test-key-abc"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"organic_results\":[]}")));

        assertThatThrownBy(() -> client.search("test", 5))
                .isInstanceOf(SerpApiSearchClient.SearchGatewayException.class)
                .hasMessageContaining("无搜索结果");
    }
}
