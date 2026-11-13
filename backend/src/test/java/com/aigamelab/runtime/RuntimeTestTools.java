package com.aigamelab.runtime;
import com.aigamelab.domain.Task;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.util.Set;
/** Lifecycle tests deliberately register fake tools. Production authorization is tested separately. */
@TestConfiguration
public class RuntimeTestTools {
    @Bean @Primary AgentTypeResolver lifecycleTestResolver(DefaultAgentTypeResolver delegate) {
        return new AgentTypeResolver() {
            public AgentType resolve(Task task){return delegate.resolve(task);}
            public String systemPrompt(AgentType type){return delegate.systemPrompt(type);}
            public Set<String> toolWhitelist(AgentType type){return null;}
        };
    }
}
