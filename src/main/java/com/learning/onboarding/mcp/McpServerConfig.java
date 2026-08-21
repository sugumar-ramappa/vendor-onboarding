package com.learning.onboarding.mcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the reference-data lookups as MCP tools.
 *
 * <h2>Why MCP rather than binding the methods directly</h2>
 * Spring AI can hand a {@code @Tool} method straight to a model, and for a
 * single-application system that would be simpler. MCP is worth the extra layer
 * for three reasons:
 *
 * <ol>
 *   <li><b>It is a boundary, not a convenience.</b> Agents reach data through a
 *       named protocol surface rather than by holding a {@code DataSource}.
 *       Combined with the read-only role, an agent has no route to the database
 *       that does not pass through a tool someone chose to publish.</li>
 *   <li><b>One place to enforce things.</b> Argument validation, result-size
 *       caps and output sanitising live at this layer, so every agent gets them
 *       whether or not its prompt behaves.</li>
 *   <li><b>It is demonstrable.</b> The same server can be pointed at any MCP
 *       client, which makes the boundary visible rather than a claim in a
 *       README.</li>
 * </ol>
 *
 * <p>The tools are also registered as ordinary {@link ToolCallback}s so the
 * in-process reviewers can call them without a round trip through the protocol.
 * Same methods, same read-only connection - MCP is the external surface, not a
 * second implementation.
 */
@Configuration
public class McpServerConfig {

    /**
     * Exposes the tools over MCP.
     *
     * <p>Without this the server starts with an empty tool list and logs
     * "No tool methods found in the provided tool objects: []" - which is easy
     * to miss, because nothing else fails.
     */
    @Bean
    public ToolCallbackProvider referenceDataToolProvider(ReferenceDataTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    /** The same tools, for direct in-process use by the reviewer agents. */
    @Bean
    public ToolCallback[] referenceDataCallbacks(ReferenceDataTools tools) {
        return ToolCallbacks.from(tools);
    }
}
