package com.melody.minirag.config;

import com.melody.minirag.mcp.McpTools;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    /** 注册 MCP 工具（与 ChatClient 工具调用共用 @Tool 注解体系）。 */
    @Bean
    MethodToolCallbackProvider miniragTools(McpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
