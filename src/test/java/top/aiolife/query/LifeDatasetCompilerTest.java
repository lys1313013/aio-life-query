package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class LifeDatasetCompilerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final QueryCompiler compiler;
    LifeDatasetCompilerTest() throws Exception { compiler = new QueryCompiler(json); }

    private QueryCompiler.Request request(QueryDatasets.Dataset dataset, String query) {
        return new QueryCompiler.Request(dataset.domain(), dataset.release(), dataset.release(), "Q", query, null);
    }
    private String where(QueryDatasets.Dataset dataset) {
        return "where:{" + dataset.rangeFields().getFirst() + ":{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}";
    }

    @ParameterizedTest @ValueSource(strings={"exercise_record","read_record","movie","b_video","performance"})
    void boundedDetailsAndStatsUseOnlyTheSelectedDataset(String name) {
        var dataset = QueryDatasets.named(name);
        var plan = compiler.compile(request(dataset, "query Q { rows:"+dataset.root()+"("+where(dataset)+",limit:2,offset:1){recordId:id} }"));
        assertEquals(name, plan.dataset()); assertEquals(2, plan.limit());
        assertEquals("Asc", plan.variables().path("orderBy").get(1).path("id").asText());
        String sums = dataset.aggregateFields().stream().map(field -> field+"{sum}").reduce("", (a,b)->a+" "+b);
        var stats = compiler.compile(request(dataset, "query Q { "+dataset.statsRoot()+"("+where(dataset)+"){count "+sums+"} }"));
        assertEquals(dataset.statsRoot(), stats.root());
        for (String forbidden : new String[]{"userId", "isDeleted", "createUser", "orderNumber", "seatInfo", "notes", "category { id }"}) {
            assertThrows(QueryException.class, () -> compiler.compile(request(dataset,"query Q { "+dataset.root()+"("+where(dataset)+"){"+forbidden+"} }")));
        }
        assertThrows(QueryException.class, () -> compiler.compile(request(dataset,"query Q { timeRecords(where:{date:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"}}){id} }")));
        assertThrows(QueryException.class, () -> compiler.compile(request(dataset,"query Q { "+dataset.root()+"(where:{id:{_eq:\"1\"}}){id} }")));
        assertThrows(QueryException.class, () -> compiler.compile(request(dataset,"query Q { "+dataset.root()+"(where:{"+dataset.rangeFields().getFirst()+":{_gte:\"2026-01-01\",_lt:\"2026-10-01\"}}){id} }")));
        assertThrows(QueryException.class, () -> compiler.compile(new QueryCompiler.Request(dataset.domain(), "time-record-v1", "time-record-v1", "Q",dataset.example(),null)));
    }

    @ParameterizedTest @ValueSource(strings={"read_record","movie"})
    void finishedRecordsCanBeQueriedWithoutRestrictingTheirCreationDate(String name) {
        var dataset = QueryDatasets.named(name);
        assertDoesNotThrow(() -> compiler.compile(request(dataset,"query Q { "+dataset.root()+"(where:{finishTime:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"},status:{_eq:\"completed\"}}){id title status} }")));
        assertThrows(QueryException.class, () -> compiler.compile(request(dataset,"query Q { "+dataset.root()+"("+"where:{createdAt:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"},status:{_eq:\"invented\"}}"+"){id} }")));
    }

    @ParameterizedTest @ValueSource(strings={"performance"})
    void currencyFiltersRequireExactDecimalStrings(String name) {
        var dataset = QueryDatasets.named(name);
        String query="query Q { performances(where:{performanceDate:{_gte:\"2026-09-01\",_lt:\"2026-10-01\"},ticketPrice:{_gte:\"19.99\"}}){id ticketPrice} }";
        assertDoesNotThrow(() -> compiler.compile(request(dataset,query)));
        assertThrows(QueryException.class, () -> compiler.compile(request(dataset,query.replace("19.99","19.999"))));
        assertThrows(QueryException.class, () -> compiler.compile(request(dataset,query.replace("\"19.99\"","19.99"))));
    }
}
