package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LifeDatasetServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AccessClient access = mock(AccessClient.class);
    private final ExecutionStore store = mock(ExecutionStore.class);
    private final BoundedHttp http = mock(BoundedHttp.class);
    private final QueryAudit audit = mock(QueryAudit.class);
    private final AccessClient.Access user = new AccessClient.Access("9007199254740993","aio-life-query","fixture","login",Long.MAX_VALUE);
    private final QueryService service;
    LifeDatasetServiceTest() throws Exception {
        service = new QueryService(access,new QueryCompiler(json),store,http,audit,
            new QueryProperties(URI.create("http://auth"),URI.create("http://hasura"),"s".repeat(32),"w".repeat(32),Path.of("unused")),json);
        when(store.acquire(user.userId())).thenReturn(mock(ExecutionStore.Lease.class));
        when(store.issue(anyString(),eq(user),anyString(),any())).thenReturn("ticket");
    }
    private QueryCompiler.Request request(QueryDatasets.Dataset dataset, String fields) {
        return new QueryCompiler.Request(dataset.domain(),dataset.release(),dataset.release(),"Recent",
            dataset.example().replace("{id}","{"+fields+"}"),json.createObjectNode().put("start","2026-09-01").put("end","2026-10-01"));
    }

    @ParameterizedTest @ValueSource(strings={"exercise_record","read_record","movie","b_video","performance"})
    void detailsPreserveIdsAndRecheckTheSameBusinessBeforeReturning(String name) throws Exception {
        var dataset=QueryDatasets.named(name);
        when(access.evaluate("token",name)).thenReturn(user);
        when(http.post(any(),anyMap(),any(),anyInt(),anyInt())).thenReturn(new BoundedHttp.Response(200,
            json.readTree("{\"data\":{\""+dataset.root()+"\":[{\"id\":9007199254740993}]}}")));
        var result=json.valueToTree(service.execute("token",request(dataset,"id")));
        assertEquals("9007199254740993",result.at("/result/"+dataset.root()+"/0/id").textValue());
        assertEquals(dataset.release(),result.path("schemaVersion").asText());
        verify(access,times(2)).evaluate("token",name); verify(access,never()).evaluate("token");
        when(access.evaluate("token",name)).thenReturn(user).thenThrow(QueryException.denied());
        assertThrows(QueryException.class,()->service.execute("token",request(dataset,"id")));
    }

    @Test void lockedDatasetIsOmittedFromCatalogWithoutHidingOtherBusinesses() {
        when(access.evaluate("token","catalog")).thenReturn(user);
        when(access.evaluate("token")).thenReturn(user);
        for(var dataset:QueryDatasets.all()) if(!dataset.name().equals("time_record")) when(access.evaluate("token",dataset.name())).thenReturn(user);
        when(access.evaluate("token","movie")).thenThrow(new QueryException("SECONDARY_LOCK_REQUIRED",403,"fixture"));
        var catalog=json.valueToTree(service.catalog("token",null));
        assertEquals(5,catalog.size());
        for(var entry:catalog) assertNotEquals("movie",entry.path("name").asText());
        assertThrows(QueryException.class,()->service.catalog("token","movie"));
    }

    @Test void currencyStaysExactAndUndeclaredFieldsAreRejected() throws Exception {
        var dataset=QueryDatasets.named("performance"); when(access.evaluate("token","performance")).thenReturn(user);
        when(http.post(any(),anyMap(),any(),anyInt(),anyInt())).thenReturn(new BoundedHttp.Response(200,
            json.readTree("{\"data\":{\"performances\":[{\"id\":\"1\",\"ticketPrice\":\"12345678.99\"}]}}")));
        assertEquals("12345678.99",json.valueToTree(service.execute("token",request(dataset,"id ticketPrice"))).at("/result/performances/0/ticketPrice").textValue());
        when(http.post(any(),anyMap(),any(),anyInt(),anyInt())).thenReturn(new BoundedHttp.Response(200,
            json.readTree("{\"data\":{\"performances\":[{\"id\":\"1\",\"ticketPrice\":\"19.99\",\"orderNumber\":\"private\"}]}}")));
        assertThrows(QueryException.class,()->service.execute("token",request(dataset,"id ticketPrice")));
    }

    @Test void webhookUsesOnlyTheDatasetStoredInTheSingleUseTicket() {
        when(store.consume("ticket")).thenReturn(new ExecutionStore.Ticket("token",user,"query","hash","movie-v1",Long.MAX_VALUE,"movie"));
        when(access.evaluate("token","movie")).thenReturn(user);
        assertEquals(Map.of("x-hasura-role","ai_movie_reader","x-hasura-user-id",user.userId()),service.webhook("ticket"));
        verify(access).evaluate("token","movie"); verify(access,never()).evaluate("token");
    }
}
