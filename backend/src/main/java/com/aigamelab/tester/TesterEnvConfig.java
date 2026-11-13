package com.aigamelab.tester;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aigamelab.gameenv.FakeGameEnvironment;
import com.aigamelab.gameenv.GameAdapter;

/**
 * T3-2：GameAdapter 装配。
 * T1 的 FakeGameEnvironment 是冻结的纯 Java 类（无 Spring 注解），
 * 在此以 @Bean 暴露为单例；未来 Web/Unity Adapter 替换此处即可，Tools 层不变。
 */
@Configuration
public class TesterEnvConfig {

    @Bean
    public GameAdapter gameAdapter() {
        return new FakeGameEnvironment();
    }
}
