package com.aigamelab.research;

import java.util.List;

/**
 * TH：真实搜索引擎后端抽象。
 *
 * <p>实现类（如 SerpApiSearchClient）按凭据配置条件注册 Bean；
 * 无凭据时无 Bean → WebSearchTool 注入 Optional.empty → BLOCKED 报错。
 */
public interface SearchGateway {

    List<SearchResult> search(String query, int numResults);

    record SearchResult(String title, String url, String snippet) {}
}
