package com.aigamelab.research;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * R0-4：HTTP 页面获取服务。
 *
 * 封装 {@link HttpClient} + Jsoup 提取，提供：
 * - fetchText(url)：获取 URL 页面纯文本（HTML 标签被 Jsoup 清理）
 * - fetchHtml(url)：获取原始 HTML
 *
 * 超时默认 10 秒，返回 null 表示获取失败（不抛异常，交 Tool 层决定是否 critical）。
 */
@Service
public class WebFetchService {

    private static final Logger log = LoggerFactory.getLogger(WebFetchService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "AIGameLab/1.0 Research Bot";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 获取 URL 页面并提取纯文本（Jsoup 清理 HTML 标签、script/style 等）。
     *
     * @param url 目标 URL
     * @return 纯文本内容；获取失败返回 null
     */
    public String fetchText(String url) {
        String html = fetchHtml(url);
        if (html == null) {
            return null;
        }
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(html);
            doc.select("script, style, noscript, nav, footer, header").remove();
            String text = doc.body() != null ? doc.body().text() : "";
            // 截断到合理长度（避免 LLM Token 爆炸）
            return text.length() > 8000 ? text.substring(0, 8000) + "\n[...截断]" : text;
        } catch (Exception e) {
            log.warn("Jsoup 解析失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 获取 URL 页面原始 HTML。
     *
     * @param url 目标 URL
     * @return 原始 HTML；获取失败返回 null
     */
    public String fetchHtml(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return response.body();
            }
            log.warn("HTTP {} for url={}", response.statusCode(), url);
            return null;
        } catch (Exception e) {
            log.warn("HTTP fetch 失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }
}
