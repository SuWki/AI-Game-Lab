package com.aigamelab.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.aigamelab.research.WebFetchService;

/**
 * R0-4：Research Tool 基础设施 Spring 配置。
 * WebFetchService 已是 @Service（自动扫描），此处显式 Import 便于追踪。
 */
@Configuration
@Import(WebFetchService.class)
public class ResearchToolConfig {
}
