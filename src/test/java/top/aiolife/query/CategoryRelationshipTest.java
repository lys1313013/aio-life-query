package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CategoryRelationshipTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String range = "date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}";

    private QueryCompiler.Plan compile(String query) throws Exception {
        var compiler = new QueryCompiler(json);
        return compiler.compile(compiler.graphqlRequest(new QueryCompiler.GraphqlRequest(query, null, null, null)));
    }

    @Test void acceptsRequestedQueryAndAggregateCategoryFilter() throws Exception {
        String query = json.readTree(Path.of("examples/sleep-before-midnight.json").toFile()).path("query").asText();
        var plan = compile(query);
        assertEquals("休息", plan.variables().at("/where/category/name/_eq").asText());
        assertEquals(0, plan.variables().at("/where/startTime/_eq").asInt());
        assertEquals(java.util.Map.of("id", "id", "name", "name"), plan.relationSelections().get("category"));
        assertTrue(compile("{timeRecordStats(where:{"+range+",category:{name:{_eq:\"休息\"}}}){count}}").query()
                .contains("filter: {where: $where}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{timeRecords(where:{category:{name:{_eq:\"休息\"}}}){category{id}}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"},category:{userId:{_eq:\"2\"}}}){id}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"},category:{name:{_like:\"%\"}}}){id}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}}){category{userId}}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}}){category{recordId}}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}}){category{name{x}}}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}}){category{id @skip(if:true)}}}",
            "{timeRecords(where:{date:{_gte:\"2026-10-01\",_lt:\"2026-11-01\"}}){category{id alias:id other:id}}}"
    })
    void rejectsInvalidAndUnboundedRelationships(String query) {
        assertThrows(QueryException.class, () -> compile(query));
    }

    @Test void normalizesNestedAliasesAndRejectsMalformedUpstreamObjects() throws Exception {
        var access = mock(AccessClient.class); var store = mock(ExecutionStore.class);
        var http = mock(BoundedHttp.class); var audit = mock(QueryAudit.class);
        var properties = new QueryProperties(URI.create("http://auth"), URI.create("http://hasura"),
                "s".repeat(32), "w".repeat(32), Path.of("unused"));
        var service = new QueryService(access, new QueryCompiler(json), store, http, audit, properties, json);
        var user = new AccessClient.Access("101", "aio-life-query", "grant", "login", Long.MAX_VALUE);
        when(access.evaluate("token")).thenReturn(user);
        when(store.acquire("101")).thenReturn(mock(ExecutionStore.Lease.class));
        when(store.issue(eq(user), anyString(), any())).thenReturn("ticket");
        var request = new QueryCompiler.GraphqlRequest("{rows:timeRecords(where:{"+range+"}){kind:category{key:id label:name}}}", null, null, null);
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                json.readTree("{\"data\":{\"rows\":[{\"kind\":{\"key\":9007199254740993,\"label\":\"休息\"}},{\"kind\":null}]}}")));
        var result = json.valueToTree(service.executeGraphql("token", request));
        assertEquals("9007199254740993", result.at("/data/rows/0/kind/key").asText());
        assertTrue(result.at("/data/rows/1/kind").isNull());
        for (String category : new String[]{"[]", "{\"key\":null,\"label\":\"休息\"}", "{\"key\":1,\"label\":2}",
                "{\"key\":1}", "{\"key\":1,\"label\":\"休息\",\"userId\":102}"}) {
            when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                    json.readTree("{\"data\":{\"rows\":[{\"kind\":"+category+"}]}}")));
            assertEquals("UPSTREAM_QUERY_FAILED", assertThrows(QueryException.class,
                    () -> service.executeGraphql("token", request)).code());
        }
    }
}
