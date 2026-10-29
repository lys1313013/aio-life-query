package top.aiolife.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QueryTransportContractTest {
    private static final String TOKEN = "fixture";
    private static final String USER_ID = "9007199254740993";
    private static final String QUERY = "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}}){id}}";
    private static final String EXECUTE_PATH = "/api/v1/queries/execute";

    @Test
    void minimalTransportPreservesAuthorizationAndExamplesCompile() throws Exception {
        ObjectMapper json = new ObjectMapper();
        QueryCompiler compiler = new QueryCompiler(json);
        AccessClient access = mock(AccessClient.class);
        ExecutionStore store = mock(ExecutionStore.class);
        BoundedHttp http = mock(BoundedHttp.class);
        QueryAudit audit = mock(QueryAudit.class);
        QueryProperties properties = new QueryProperties(
                URI.create("http://auth"), URI.create("http://hasura"),
                "s".repeat(32), "w".repeat(32), Path.of("unused"));
        QueryService service = new QueryService(access, compiler, store, http, audit, properties, json);
        AccessClient.Access user = new AccessClient.Access(
                USER_ID, "aio-life-query", "fixture", "login", Long.MAX_VALUE);

        doReturn(user).when(access).evaluate(TOKEN);
        when(store.acquire(user.userId())).thenReturn(mock(ExecutionStore.Lease.class));
        when(store.issue(eq(user), anyString(), any())).thenReturn("ticket");
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(
                new BoundedHttp.Response(200,
                        json.readTree("{\"data\":{\"timeRecords\":[{\"id\":9007199254740993}]}}")));

        MockMvc rest = MockMvcBuilders.standaloneSetup(new QueryController(service, properties))
                .setControllerAdvice(new QueryErrors())
                .addFilters(new RequestBoundaryFilter())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
        assertRestRequestContract(rest, json);

        when(access.evaluate(TOKEN)).thenThrow(QueryException.denied());
        rest.perform(restRequest(json, EXECUTE_PATH, Map.of("query", QUERY)))
                .andExpect(status().isForbidden());
        doReturn(user).when(access).evaluate(TOKEN);

        assertMinimalMcpRequest(service, json);
        assertCurrentExamplesCompile(compiler, json);
    }

    private void assertRestRequestContract(MockMvc rest, ObjectMapper json) throws Exception {
        rest.perform(restRequest(json, EXECUTE_PATH, Map.of("query", QUERY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.timeRecords[0].id").value(USER_ID));

        for (String obsoleteField : List.of("domain", "schemaVersion", "policyVersion", "operationName")) {
            rest.perform(restRequest(json, EXECUTE_PATH, Map.of("query", QUERY, obsoleteField, "old")))
                    .andExpect(status().isBadRequest());
        }

        rest.perform(restRequest(json, "/api/v1/queries/validate",
                        Map.of("query", QUERY, "expectedRelease", "old")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.rscode").value("SCHEMA_CHANGED"));
    }

    private MockHttpServletRequestBuilder restRequest(ObjectMapper json, String path,
                                                      Map<String, Object> body) throws Exception {
        return post(path)
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body));
    }

    private void assertMinimalMcpRequest(QueryService service, ObjectMapper json) throws Exception {
        QueryMcpConfiguration configuration = new QueryMcpConfiguration();
        var protocolJson = configuration.queryMcpJsonMapper(json);
        var transport = configuration.queryMcpTransport(protocolJson);
        var server = configuration.queryMcpServer(transport, service, json, protocolJson);
        try {
            MockMvc mcp = MockMvcBuilders.routerFunctions(
                    configuration.queryMcpRouter(transport, server)).build();
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", "aio_query_execute", "arguments", Map.of("query", QUERY)));

            mcp.perform(post("/mcp")
                            .requestAttr(McpAccessFilter.TOKEN_ATTRIBUTE, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                            .content(json.writeValueAsBytes(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.isError").value(false))
                    .andExpect(jsonPath("$.result.structuredContent.data.result.timeRecords[0].id").value(USER_ID));
        } finally {
            server.close();
        }
    }

    private void assertCurrentExamplesCompile(QueryCompiler compiler, ObjectMapper json) throws Exception {
        int compiledExamples = 0;
        try (var paths = Files.list(Path.of("examples"))) {
            List<Path> examples = paths.filter(path -> path.toString().endsWith(".json")).toList();
            for (Path example : examples) {
                JsonNode body = json.readTree(example.toFile());
                if (body.has("params")) {
                    body = body.path("params").path("arguments");
                }
                if (!body.has("query")) {
                    continue;
                }

                String filename = example.getFileName().toString();
                QueryCompiler.Request resolved;
                if (filename.equals("graphql-query.json") || filename.equals("sleep-before-midnight.json")) {
                    resolved = compiler.graphqlRequest(json.treeToValue(body, QueryCompiler.GraphqlRequest.class));
                } else {
                    resolved = compiler.resolve(json.treeToValue(body, QueryCompiler.QueryRequest.class));
                }
                compiler.compile(resolved);
                compiledExamples++;
            }
        }
        assertEquals(10, compiledExamples);
    }
}
