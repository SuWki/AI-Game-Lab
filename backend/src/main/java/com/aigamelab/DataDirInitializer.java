package com.aigamelab;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * SQLite 数据目录保障与路径归一。
 * 两个职责：
 * 1. SQLite 驱动不会自动创建 db 文件的父目录，全新机器首次启动会失败——提前建目录；
 * 2. JDBC URL 中的 ./data 相对于进程工作目录：mvn spring-boot:run 从 backend/ 起跑、
 *    java -jar 可能从仓库根起跑，会导致数据库落点漂移——这里统一归一为
 *    <仓库根>/data/agl.db（README 约定的位置），与启动方式无关。
 *
 * 不自定义 DataSource bean（避免关闭 Boot 自动配置、破坏 hikari 单连接池语义），
 * WAL / busy_timeout 等 PRAGMA 仍由 application.yml 中 JDBC URL 参数承担。
 */
public class DataDirInitializer implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return;
        }
        int q = url.indexOf('?');
        String prefix = q > 0 ? url.substring(0, q) : url;
        String params = q > 0 ? url.substring(q) : "";
        String file = prefix.substring("jdbc:sqlite:".length());

        // 仅归一化相对路径；已是绝对路径则只保证父目录存在
        File dbFile = new File(file);
        if (!dbFile.isAbsolute()) {
            // 工作目录为 backend/（Maven 工程）时取其父目录作为仓库根
            File cwd = new File("").getAbsoluteFile();
            File root = "backend".equals(cwd.getName()) ? cwd.getParentFile() : cwd;
            dbFile = new File(root, file);
            String normalized = "jdbc:sqlite:" + dbFile.getPath().replace('\\', '/') + params;
            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", normalized);
            environment.getPropertySources().addFirst(new MapPropertySource("sqliteDataDir", props));
        }

        File parent = dbFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
    }

    @Override
    public int getOrder() {
        // 在配置文件加载完成后（能读到 spring.datasource.url）、
        // DataSource 自动配置前执行
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
