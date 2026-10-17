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

class FullFieldQueryTest {
    private final ObjectMapper json = new ObjectMapper();

    private QueryCompiler.Request request(String name, String selection, String filter) {
        var d = QueryDatasets.named(name);
        return new QueryCompiler.Request(d.domain(), d.release(), d.release(), "Q",
                "query Q { " + d.root() + "(where:{" + d.rangeFields().getFirst()
                        + ":{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}" + filter + "}){" + selection + "}}", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"time_record", "exercise_record", "read_record", "movie", "b_video", "performance"})
    void everyPhysicalFieldCanBeSelectedWhileTheDatasetRoleStillControlsExecution(String name) throws Exception {
        var d = QueryDatasets.named(name);
        var compiler = new QueryCompiler(json);
        var request = request(name, String.join(" ", d.fields().keySet()), ",userId:{_eq:\"9007199254740993\"},isDeleted:{_eq:0}");
        var plan = compiler.compile(request);
        assertEquals(d.fields().keySet(), plan.selectedFields().keySet());
        assertEquals("9007199254740993", plan.variables().at("/where/userId/_eq").asText());
        var access = mock(AccessClient.class);
        var store = mock(ExecutionStore.class);
        var http = mock(BoundedHttp.class);
        var audit = mock(QueryAudit.class);
        var user = new AccessClient.Access("9007199254740993", "aio-life-query", "grant", "login", Long.MAX_VALUE);
        if (name.equals("time_record")) when(access.evaluate("token")).thenReturn(user);
        else when(access.evaluate("token", name)).thenReturn(user);
        when(store.acquire(user.userId())).thenReturn(mock(ExecutionStore.Lease.class));
        when(store.issue(eq(user), anyString(), any())).thenReturn("ticket");
        var row = json.createObjectNode();
        d.fields().forEach((field, definition) -> {
            switch (definition.kind()) {
                case "ID" -> row.put(field, 9007199254740993L);
                case "INT" -> row.put(field, 0);
                case "JSON" -> row.putArray(field).addObject().put("page", 1).put("part", "章节");
                default -> row.put(field, "fixture");
            }
            if (definition.kind().equals("DECIMAL")) row.put(field, "19.99");
        });
        var upstream = json.createObjectNode();
        upstream.putObject("data").putArray(d.root()).add(row);
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200, upstream));
        var service = new QueryService(access, compiler, store, http, audit, new QueryProperties(
                URI.create("http://auth"), URI.create("http://hasura"), "s".repeat(32), "w".repeat(32), Path.of("unused")), json);
        var result = json.valueToTree(service.execute("token", request)).at("/result/" + d.root() + "/0");
        assertEquals("9007199254740993", result.path("userId").textValue());
        assertEquals("9007199254740993", result.path("createUser").textValue());
        assertEquals(0, result.path("isDeleted").intValue());
        if (name.equals("b_video")) {
            assertEquals("9007199254740993", result.path("coverVersion").textValue());
            assertTrue(result.path("pagesInfo").isArray());
            assertEquals("章节", result.at("/pagesInfo/0/part").textValue());
        }
        if (name.equals("time_record")) verify(access, times(1)).evaluate("token");
        else verify(access, times(1)).evaluate("token", name);
    }

    @Test void timeOfDayFiltersAreBoundedAndDoNotRequireReadingAllDetails() throws Exception {
        var compiler = new QueryCompiler(json);
        var request = request("time_record", "startTime endTime title description", ",startTime:{_eq:0},endTime:{_lte:1440}");
        assertEquals(0, compiler.compile(request).variables().at("/where/startTime/_eq").intValue());
        assertThrows(QueryException.class, () -> compiler.compile(request("time_record", "id", ",startTime:{_eq:1441}")));
        assertThrows(QueryException.class, () -> compiler.compile(request("time_record", "id", ",endTime:{_eq:-1}")));
        var d = QueryDatasets.named("time_record");
        var stats = new QueryCompiler.Request(d.domain(), d.release(), d.release(), "Q",
                "query Q { timeRecordStats(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"},startTime:{_eq:0},categoryId:{_eq:\"1\"}}){count} }", null);
        assertEquals("timeRecordStats", compiler.compile(stats).root());
        assertThrows(QueryException.class, () -> compiler.compile(new QueryCompiler.Request(d.domain(), "time-record-v1", "time-record-v1", "Q", stats.query(), null)));
    }

    @Test void jsonOutputDoesNotEnableArbitraryJsonInputOrRelations() throws Exception {
        var compiler = new QueryCompiler(json);
        assertThrows(QueryException.class, () -> compiler.compile(request("b_video", "id", ",pagesInfo:{_eq:\"[]\"}")));
        assertThrows(QueryException.class, () -> compiler.compile(request("b_video", "pagesInfo { page }", "")));
    }
}
