package top.aiolife.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QueryMcpCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final QueryService service = mock(QueryService.class);
    private McpStatelessSyncServer server;
    private MockMvc mvc;

    @BeforeEach void setup() {
        var config = new QueryMcpConfiguration();
        var protocolJson = config.queryMcpJsonMapper(json);
        var transport = config.queryMcpTransport(protocolJson);
        server = config.queryMcpServer(transport, service, json, protocolJson);
        mvc = MockMvcBuilders.routerFunctions(config.queryMcpRouter(transport, server)).build();
    }

    @AfterEach void close() { server.close(); }

    @Test void codexInitializeAcceptsExtendedClientCapabilities() throws Exception {
        mvc.perform(post("/mcp").requestAttr(McpAccessFilter.TOKEN_ATTRIBUTE, "fixture-token")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                                  "protocolVersion":"2025-06-18",
                                  "capabilities":{"elicitation":{"form":{},"url":{}},"sampling":{"tools":{}},"futureCapability":{}},
                                  "clientInfo":{"name":"codex","version":"0.160.0"}}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.serverInfo.name").value("aio-life-query"));
        assertTrue(json.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        verifyNoInteractions(service);
    }

    @Test void protocolToleranceDoesNotAllowForgedBusinessIdentity() throws Exception {
        mvc.perform(post("/mcp").requestAttr(McpAccessFilter.TOKEN_ATTRIBUTE, "fixture-token")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                                  "name":"aio_query_execute","arguments":{
                                    "domain":"time","schemaVersion":"time-record-v3","policyVersion":"time-record-v3",
                                    "operationName":"Q","query":"query Q { timeRecords { id } }","userId":"another-user"}}}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.rscode").value("QUERY_UNSUPPORTED"));
        verifyNoInteractions(service);
    }
}
