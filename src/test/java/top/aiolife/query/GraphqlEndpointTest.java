package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GraphqlEndpointTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AccessClient access = mock(AccessClient.class);
    private final ExecutionStore store = mock(ExecutionStore.class);
    private final BoundedHttp http = mock(BoundedHttp.class);
    private final QueryAudit audit = mock(QueryAudit.class);
    private final AccessClient.Access user = new AccessClient.Access("9007199254740993", "aio-life-query", "grant", "login", Long.MAX_VALUE);
    private static final String QUERY = "{ timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){id} }";
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        var properties = new QueryProperties(URI.create("http://auth/evaluate"), URI.create("http://hasura/graphql"),
                "s".repeat(32), "w".repeat(32), Path.of("unused"));
        var service = new QueryService(access, new QueryCompiler(json), store, http, audit, properties, json);
        mvc = MockMvcBuilders.standaloneSetup(new GraphqlController(service))
                .setControllerAdvice(new QueryErrors()).addFilters(new RequestBoundaryFilter())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(access.evaluate("token")).thenReturn(user);
        when(store.acquire(user.userId())).thenReturn(mock(ExecutionStore.Lease.class));
        when(store.issue(eq(user), anyString(), any())).thenReturn("ticket");
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(
                new BoundedHttp.Response(200, json.readTree("{\"data\":{\"timeRecords\":[{\"id\":9007199254740993}]}}")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query(String query) throws Exception {
        return post("/graphql").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                .accept("application/graphql-response+json").content(json.writeValueAsBytes(Map.of("query", query)));
    }

    @Test void anonymousQueryUsesSharedAuthorizationTicketAuditAndGraphqlEnvelope() throws Exception {
        mvc.perform(query(QUERY)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/graphql-response+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.timeRecords[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.extensions.dataset").value("time_record"))
                .andExpect(jsonPath("$.extensions.queryId").isString())
                .andExpect(jsonPath("$.rscode").doesNotExist()).andExpect(jsonPath("$.errors").doesNotExist());
        verify(access).evaluate("token");
        verifyNoMoreInteractions(access);
        verify(store, times(1)).rate(user.userId());
        verify(http).post(any(), eq(Map.of("Authorization", "Bearer ticket")), any(), anyInt(), anyInt());
        verify(store).discard("ticket");
        verify(audit).write(anyString(), eq(user), eq("SUCCEEDED"), anyString(), eq(1));
    }

    @Test void namedQuerySupportsVariablesAliasesAndJsonClients() throws Exception {
        String query = "query Recent($start:String!,$end:String!){rows:timeRecords(where:{date:{_gte:$start,_lt:$end}}){key:id}}";
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                json.readTree("{\"data\":{\"rows\":[{\"key\":\"r1\"}]}}")));
        mvc.perform(post("/graphql").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("query", query,
                                "operationName", "Recent", "variables", Map.of("start", "2026-09-01", "end", "2026-10-01")))))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.rows[0].key").value("r1"));
    }

    @Test void routesEveryPublishedDatasetAndAggregateThroughItsOwnPermission() throws Exception {
        for (var dataset : QueryDatasets.all()) {
            if (!dataset.name().equals("time_record")) when(access.evaluate("token", dataset.name())).thenReturn(user);
            for (String root : new String[]{dataset.root(), dataset.statsRoot()}) {
                String query = "query Q { " + root + "(where:{" + dataset.rangeFields().getFirst()
                        + ":{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){" + (root.equals(dataset.root()) ? "id" : "count") + "} }";
                String data = root.equals(dataset.root()) ? "[]" : "{\"count\":\"0\"}";
                when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                        json.readTree("{\"data\":{\"" + root + "\":" + data + "}}")));
                mvc.perform(query(query)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.extensions.dataset").value(dataset.name()));
            }
            if (!dataset.name().equals("time_record")) verify(access, times(2)).evaluate("token", dataset.name());
        }
    }

    @Test void missingOrInvalidCredentialCannotExecuteOrAudit() throws Exception {
        mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("query", QUERY))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errors[0].extensions.code").value("AUTH_REQUIRED"));
        when(access.evaluate("token")).thenThrow(new QueryException("AUTH_REQUIRED", 401, "凭据已失效"));
        mvc.perform(query(QUERY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(store, audit, http);
    }

    @Test void lockedDatasetIsDeniedAndRevocationAppliesToTheNextRequest() throws Exception {
        when(access.evaluate("token")).thenThrow(new QueryException("SECONDARY_LOCK_REQUIRED", 403, "需要解锁"));
        mvc.perform(query(QUERY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("SECONDARY_LOCK_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(http, audit);
        doReturn(user).doThrow(QueryException.denied()).when(access).evaluate("token");
        mvc.perform(query(QUERY)).andExpect(status().isOk()).andExpect(jsonPath("$.data.timeRecords").isArray());
        mvc.perform(query(QUERY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.data").doesNotExist());
        verify(store).discard("ticket");
        verify(http).post(any(), anyMap(), any(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mutation Q { timeRecords {id} }", "subscription Q { timeRecords {id} }",
            "{ __schema { queryType { name } } }", "{ __type(name:\"Query\") { name } }",
            "{ timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){password} }",
            "{ timeRecords(where:{accountId:{_eq:\"2\"},date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){id} }",
            "{ timeRecords(where:{date:{_gte:\"2026-01-01\",_lt:\"2027-01-03\"}}){id} }",
            "{ a:timeRecords{ id } b:timeRecords{ id } }",
            "query A { timeRecords {id} } query B { timeRecords {id} }",
            "query Q { ...F } fragment F on Query { timeRecords {id} }"
    })
    void unsupportedOperationsCannotReachHasura(String query) throws Exception {
        mvc.perform(query(query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("QUERY_UNSUPPORTED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(http);
    }

    @Test void mismatchedOperationAndUnsupportedExtensionsAreRejected() throws Exception {
        for (var body : new Object[]{Map.of("query", "query Q " + QUERY, "operationName", "Other"),
                Map.of("query", QUERY, "extensions", Map.of("userId", "2")),
                Map.of("query", QUERY, "userId", "2"), Map.of("query", QUERY, "variables", "invalid"),
                new Object[]{Map.of("query", QUERY)}}) {
            mvc.perform(post("/graphql").header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        }
        verifyNoInteractions(http);
    }

    @Test void forgedHasuraHeadersAndOversizedBodiesUseGraphqlErrorsBeforeExecution() throws Exception {
        mvc.perform(query(QUERY).header("X-Hasura-User-Id", "2"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors[0].extensions.code").value("ACCESS_DENIED"));
        mvc.perform(query(QUERY).content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.errors[0].extensions.code").value("QUERY_LIMIT_EXCEEDED"));
        mvc.perform(query(QUERY).header("Content-Encoding", "gzip"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(access, store, audit, http);
    }

    @Test void methodMediaTypeAndMalformedJsonHaveHttpErrors() throws Exception {
        mvc.perform(get("/graphql")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/graphql").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(query(QUERY).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        verifyNoInteractions(access, http);
    }

    @Test void rateLimitAndUpstreamPartialFailureNeverReturnData() throws Exception {
        doThrow(new QueryException("QUERY_LIMIT_EXCEEDED", 429, "请求过于频繁")).when(store).rate(user.userId());
        mvc.perform(query(QUERY)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(audit, http);
        doNothing().when(store).rate(user.userId());
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                json.readTree("{\"data\":{\"timeRecords\":[]},\"errors\":[{\"message\":\"secret\"}]}")));
        mvc.perform(query(QUERY)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("UPSTREAM_QUERY_FAILED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
