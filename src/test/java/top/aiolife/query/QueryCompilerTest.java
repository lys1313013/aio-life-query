package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class QueryCompilerTest {
    private final ObjectMapper json = new ObjectMapper();
    private QueryCompiler compiler;
    @BeforeEach void setup() throws Exception { compiler = new QueryCompiler(json); }
    private QueryCompiler.Request request(String query, ObjectNode variables) {
        return new QueryCompiler.Request("time", QueryProperties.RELEASE, QueryProperties.RELEASE, "Q", query, variables);
    }
    private String query(String selection) {
        return "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}) { " + selection + " } }";
    }

    @Test void minimalRequestInfersDatasetAndAcceptsAnonymousQueries() {
        String source = query("id").replace("query Q", "");
        var resolved = compiler.resolve(new QueryCompiler.QueryRequest(source, null, null));
        var plan = compiler.compile(resolved);
        assertEquals("time_record", plan.dataset());
        assertEquals(100, plan.limit());
        var stale = assertThrows(QueryException.class, () -> compiler.resolve(
                new QueryCompiler.QueryRequest(source, null, "outdated-release")));
        assertEquals("SCHEMA_CHANGED", stale.code());
        assertDoesNotThrow(() -> compiler.compile(compiler.resolve(
                new QueryCompiler.QueryRequest(source, null, QueryProperties.RELEASE))));
    }

    @Test void normalizesVariablesAliasesAndStablePagination() {
        var vars = json.createObjectNode().put("start", "2026-09-01").put("end", "2026-10-01").put("n", 2);
        var plan = compiler.compile(request("query Q($start:String!,$end:String!,$n:Int!){ rows:timeRecords(where:{date:{_gte:$start,_lt:$end}},limit:$n){ recordId:id categoryId durationMinutes } }", vars));
        assertEquals("rows", plan.responseKey()); assertEquals(2, plan.limit());
        assertEquals("id", plan.selectedFields().get("recordId"));
        assertFalse(plan.query().contains("2026-09"));
        assertEquals("Asc", plan.variables().path("orderBy").get(1).path("id").asText());
    }

    @Test void supportsExplicitAggregateWithoutLoadingDetails() {
        var plan = compiler.compile(request("query Q { timeRecordStats(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}) { count durationMinutes { sum } } }", null));
        assertEquals("timeRecordStats", plan.root()); assertFalse(plan.query().contains("timeRecords("));
    }

    @ParameterizedTest
    @CsvSource({
            "date,2026-01-01,2027-01-01,true",
            "date,2024-01-01,2025-01-01,true",
            "date,2024-01-01,2025-01-02,false",
            "createdAt,2024-01-01T12:00:00,2025-01-01T12:00:00,true",
            "createdAt,2024-01-01T12:00:00,2025-01-01T12:00:01,false"
    })
    void enforces366DayBoundaryForDetailsAndAggregates(String field, String start, String end, boolean allowed) {
        for (String root : new String[]{"timeRecords", "timeRecordStats"}) {
            String selection = root.equals("timeRecords") ? "id" : "count";
            var request = compiler.graphqlRequest(new QueryCompiler.GraphqlRequest(
                    "query Q($start:String!,$end:String!){" + root + "(where:{" + (field.equals("date") ? "" : "date:{_gte:\"2024-01-01\",_lt:\"2025-01-01\"},") + field
                            + ":{_gte:$start,_lt:$end}}){" + selection + "}}", "Q",
                    json.createObjectNode().put("start", start).put("end", end), null));
            if (allowed) assertDoesNotThrow(() -> compiler.compile(request));
            else assertThrows(QueryException.class, () -> compiler.compile(request));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "unknownField", "__typename", "category { userId }"})
    void rejectsHiddenOutputAndRelations(String selection) { assertThrows(QueryException.class, () -> compiler.compile(request(query(selection), null))); }

    @ParameterizedTest
    @ValueSource(strings = {
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"},accountId:{_eq:\"2\"}}){id}}",
            "query Q { timeRecords(where:{date:{_gte:\"2025-01-01\",_lt:\"2026-10-01\"}}){id}}",
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-09-01\"}}){id}}",
            "query Q { timeRecords(where:{date:{_gte:\"2026-02-30\",_lt:\"2026-03-01\"}}){id}}",
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}},limit:1001){id}}",
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}},orderBy:[{accountId:Asc}]){id}}",
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){id @skip(if:true)}}",
            "query Q { __schema { types { name } } }",
            "mutation Q { deleteTimeRecords { id } }",
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){...F}} fragment F on TimeRecord {id}",
            "query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){id}} query Other {__typename}"
    })
    void rejectsBypassesAndUnboundedQueries(String query) { assertThrows(QueryException.class, () -> compiler.compile(request(query, null))); }

    @Test void validatesExpandedVariableValuesAndOldSchema() throws Exception {
        var vars = (ObjectNode) json.readTree("{\"where\":{\"date\":{\"_gte\":\"2026-09-01\",\"_lt\":\"2026-10-01\"},\"accountId\":{\"_eq\":\"2\"}}}");
        assertThrows(QueryException.class, () -> compiler.compile(request("query Q($where:TimeRecordBoolExp!){timeRecords(where:$where){id}}", vars)));
        assertThrows(QueryException.class, () -> compiler.compile(new QueryCompiler.Request("time", "old", QueryProperties.RELEASE, "Q", query("id"), null)));
    }
}
