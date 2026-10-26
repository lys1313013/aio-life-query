package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasetAccessClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private final BoundedHttp http=mock(BoundedHttp.class);
    private final AccessClient client=new AccessClient(http,new QueryProperties(URI.create("http://auth"),URI.create("http://hasura"),"s".repeat(32),"w".repeat(32),Path.of("unused")));
    private Map<String,Object> decision(String role,String scope) {
        return Map.of("userId","9007199254740993","appId","aio-life-query","role",role,"scopes",List.of(scope),"credentialId","a".repeat(32),"credentialType","login","expiresAt",Long.MAX_VALUE);
    }
    @ParameterizedTest @ValueSource(strings={"exercise_record","read_record","movie","b_video","performance"})
    void roleAndScopeMustMatchTheRequestedDataset(String name) {
        var dataset=QueryDatasets.named(name);
        {
            var body=json.createObjectNode().put("code",0);
            body.set("data",json.valueToTree(decision(dataset.role(),dataset.scope())));
            when(http.post(any(),anyMap(),eq(Map.of("dataset",name)),anyInt(),anyInt())).thenReturn(new BoundedHttp.Response(200,body));
            assertEquals("9007199254740993",client.evaluate("token",name).userId());
            body.set("data",json.valueToTree(decision("ai_time_reader","time.read")));
            assertThrows(QueryException.class,()->client.evaluate("token",name));
        }
    }
    @Test void malformedMissingOrConflictingCodesNeverAuthorizeAndModernLocksArePreserved() throws Exception {
        for(String raw:List.of("{\"code\":\"0\"}","{\"code\":0.1}","{}","{\"rscode\":\"0\"}","{\"code\":0,\"rscode\":\"2001\"}")) {
            var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(raw);
            body.set("data",json.valueToTree(decision("ai_movie_reader","movie.read")));
            when(http.post(any(),anyMap(),any(),anyInt(),anyInt())).thenReturn(new BoundedHttp.Response(200,body));
            assertThrows(QueryException.class,()->client.evaluate("token","movie"));
        }
        when(http.post(any(),anyMap(),any(),anyInt(),anyInt())).thenReturn(new BoundedHttp.Response(200,json.readTree("{\"code\":2001}")));
        assertEquals("SECONDARY_LOCK_REQUIRED",assertThrows(QueryException.class,()->client.evaluate("token","movie")).code());
    }
}
