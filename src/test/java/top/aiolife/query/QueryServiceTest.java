package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AccessClient access = mock(AccessClient.class);
    private final ExecutionStore store = mock(ExecutionStore.class);
    private final BoundedHttp http = mock(BoundedHttp.class);
    private final QueryAudit audit = mock(QueryAudit.class);
    private final AccessClient.Access user = new AccessClient.Access("9007199254740993", "aio-life-query", "grant", "login", Long.MAX_VALUE);
    private QueryService service;
    private QueryCompiler.Request request;

    @BeforeEach void setup() throws Exception {
        var properties = new QueryProperties(URI.create("http://auth/evaluate"), URI.create("http://hasura/graphql"), "s".repeat(32), "w".repeat(32), Path.of("unused"));
        service = new QueryService(access, new QueryCompiler(json), store, http, audit, properties, json);
        request = new QueryCompiler.Request("time", QueryProperties.RELEASE, QueryProperties.RELEASE, "Q",
                "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){id categoryId durationMinutes} }", null);
        when(access.evaluate("token")).thenReturn(user);
        when(store.acquire(user.userId())).thenReturn(mock(ExecutionStore.Lease.class));
        when(store.issue(eq(user), anyString(), any())).thenReturn("ticket");
    }

    @Test void preservesBigintAndAuthorizesOnlyAtEntry() throws Exception {
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                json.readTree("{\"data\":{\"timeRecords\":[{\"id\":\"r1\",\"categoryId\":9007199254740993,\"durationMinutes\":60}]}}")));
        var result = json.valueToTree(service.execute("token", request));
        assertEquals("9007199254740993", result.at("/result/timeRecords/0/categoryId").textValue());
        verify(access).evaluate("token"); verifyNoMoreInteractions(access); verify(store).discard("ticket");
        verify(audit).write(anyString(), eq(user), eq("SUCCEEDED"), anyString(), eq(1));
    }

    @Test void discardsPartialGraphqlData() throws Exception {
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                json.readTree("{\"data\":{\"timeRecords\":[]},\"errors\":[{\"message\":\"secret database detail\"}]}")));
        var error = assertThrows(QueryException.class, () -> service.execute("token", request));
        assertFalse(error.getMessage().contains("secret")); verify(audit, never()).write(anyString(), any(), eq("SUCCEEDED"), any(), anyInt());
    }

    @Test void revocationTakesEffectOnTheNextRequest() throws Exception {
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200, json.readTree("{\"data\":{\"timeRecords\":[]}}")));
        when(access.evaluate("token")).thenReturn(user).thenThrow(QueryException.denied());
        assertDoesNotThrow(() -> service.execute("token", request));
        assertThrows(QueryException.class, () -> service.execute("token", request));
        verify(access, times(2)).evaluate("token");
        verify(http).post(any(), anyMap(), any(), anyInt(), anyInt());
        verify(audit).write(anyString(), eq(user), eq("SUCCEEDED"), anyString(), eq(0));
    }

    @Test void auditFailurePreventsDatabaseExecution() {
        doThrow(new QueryException("AUDIT_UNAVAILABLE",503,"fixture")).when(audit).write(anyString(), any(), anyString(), any(), anyInt());
        assertThrows(QueryException.class, () -> service.execute("token", request)); verifyNoInteractions(http);
    }

    @Test void authenticationFailureNeverTouchesAuditOrExecutionDependencies() {
        var denied = new QueryException("AUTH_REQUIRED", 401, "fixture");
        when(access.evaluate("invalid-token")).thenThrow(denied);
        assertSame(denied, assertThrows(QueryException.class, () -> service.execute("invalid-token", request)));
        verifyNoInteractions(audit, store, http);
    }

    @Test void rateRejectionNeverTouchesAuditOrDatabase() {
        var limited = new QueryException("QUERY_LIMIT_EXCEEDED", 429, "fixture");
        doThrow(limited).when(store).rate(user.userId());
        assertSame(limited, assertThrows(QueryException.class, () -> service.execute("token", request)));
        verifyNoInteractions(audit, http);
        verify(store, never()).acquire(anyString());
    }

    @Test void admittedUnsupportedQueryIsStillAuditedWithoutDatabaseExecution() {
        var invalid = new QueryCompiler.Request("time", QueryProperties.RELEASE, QueryProperties.RELEASE,
                "Q", "mutation Q { invalid }", null);
        assertThrows(QueryException.class, () -> service.execute("token", invalid));
        verify(audit).write(anyString(), eq(user), eq("STARTED"), isNull(), eq(0));
        verify(audit).write(anyString(), eq(user), eq("QUERY_UNSUPPORTED"), isNull(), eq(0));
        verifyNoInteractions(http);
    }
}
