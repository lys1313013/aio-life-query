package top.aiolife.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Configuration
public class QueryMcpConfiguration {
    private static final String TOKEN = "bearer";

    @Bean JacksonMcpJsonMapper queryMcpJsonMapper(ObjectMapper json) {
        // MCP 能力字段可向前扩展；业务请求仍使用原来的严格 ObjectMapper。
        return new JacksonMcpJsonMapper(json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Bean WebMvcStatelessServerTransport queryMcpTransport(JacksonMcpJsonMapper protocolJson) {
        return WebMvcStatelessServerTransport.builder().messageEndpoint("/mcp")
                .jsonMapper(protocolJson)
                .contextExtractor(request -> {
                    Object token = request.servletRequest().getAttribute(McpAccessFilter.TOKEN_ATTRIBUTE);
                    if (!(token instanceof String value)) throw QueryException.denied();
                    return McpTransportContext.create(Map.of(TOKEN, value));
                }).build();
    }

    @Bean(destroyMethod = "close") McpStatelessSyncServer queryMcpServer(
            WebMvcStatelessServerTransport transport, QueryService service, ObjectMapper json,
            JacksonMcpJsonMapper protocolJson) {
        var queryProperties = Map.<String, Object>of(
                "domain", Map.of("type", "string", "enum", List.of("time")),
                "schemaVersion", Map.of("type", "string", "enum", List.of(QueryProperties.RELEASE)),
                "policyVersion", Map.of("type", "string", "enum", List.of(QueryProperties.RELEASE)),
                "operationName", Map.of("type", "string", "minLength", 1),
                "query", Map.of("type", "string", "minLength", 1, "maxLength", 32768),
                "variables", Map.of("type", "object"));
        var required = List.of("domain", "schemaVersion", "policyVersion", "operationName", "query");
        return McpServer.sync(transport).serverInfo("aio-life-query", "0.1.0")
                .jsonMapper(protocolJson)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .instructions("先读取 time_record 结构，再预检并执行受限只读 GraphQL。身份只由 HTTP Bearer 凭据确定。")
                .tools(List.of(
                        tool("aio_query_list_datasets", "读取当前用户可用的数据集目录", Map.of(), List.of(), json,
                                (token, args) -> service.catalog(token, null)),
                        tool("aio_query_describe_dataset", "读取 time_record 字段、GraphQL SDL、版本和限制",
                                Map.of("name", Map.of("type", "string", "enum", List.of("time_record"))), List.of("name"), json,
                                (token, args) -> service.catalog(token, (String) args.get("name"))),
                        tool("aio_query_validate", "预检时迹只读查询；执行时仍重新检查权限", queryProperties, required, json,
                                (token, args) -> service.validate(token, request(json, args))),
                        tool("aio_query_execute", "执行当前用户的时迹明细或汇总查询", queryProperties, required, json,
                                (token, args) -> service.execute(token, request(json, args)))))
                .build();
    }

    @Bean RouterFunction<ServerResponse> queryMcpRouter(WebMvcStatelessServerTransport transport,
                                                       McpStatelessSyncServer server) {
        return transport.getRouterFunction();
    }

    private static QueryCompiler.Request request(ObjectMapper json, Map<String, Object> args) {
        try { return json.convertValue(args, QueryCompiler.Request.class); }
        catch (IllegalArgumentException error) { throw QueryException.invalid(); }
    }

    private static McpStatelessServerFeatures.SyncToolSpecification tool(String name, String description,
            Map<String, Object> properties, List<String> required, ObjectMapper json,
            BiFunction<String, Map<String, Object>, Object> call) {
        var schema = new McpSchema.JsonSchema("object", properties, required, false, null, null);
        var tool = McpSchema.Tool.builder().name(name).description(description).inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, false)).build();
        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (context, request) -> {
            Map<String, Object> output;
            boolean failed = false;
            try {
                var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                // JSON Schema 是发现协议；服务端也明确拒绝身份/角色等未知参数。
                if (!properties.keySet().containsAll(args.keySet()) || !args.keySet().containsAll(required)) throw QueryException.invalid();
                var token = context.get(TOKEN);
                if (!(token instanceof String value)) throw QueryException.denied();
                output = QueryController.success(call.apply(value, args));
            } catch (QueryException error) {
                failed = true; output = Map.of("rscode", error.code(), "result", error.getMessage());
            } catch (RuntimeException error) {
                failed = true; output = Map.of("rscode", "UPSTREAM_UNAVAILABLE", "result", "查询依赖不可用");
            }
            try {
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json.writeValueAsString(output))), failed, output, null);
            } catch (java.io.IOException error) { throw new IllegalStateException("MCP result serialization failed"); }
        });
    }
}
