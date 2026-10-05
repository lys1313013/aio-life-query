package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessClientTest {
    private final BoundedHttp http = mock(BoundedHttp.class);
    private final QueryProperties properties = new QueryProperties(URI.create("http://auth/check-token"),
            URI.create("http://hasura/graphql"), "s".repeat(32), "w".repeat(32), Path.of("unused"));
    private final AccessClient client = new AccessClient(http, properties);

    @Test void forwardsOriginalCredentialAndOnlyFixedDatasetToAuthServer() throws Exception {
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                new ObjectMapper().readTree("{\"rscode\":\"0\",\"data\":{\"userId\":\"9007199254740993\",\"appId\":\"aio-life-query\",\"role\":\"ai_time_reader\",\"scopes\":[\"time.read\"],\"credentialId\":\"" + "a".repeat(32) + "\",\"credentialType\":\"api_key\",\"expiresAt\":9223372036854775807}}")));
        assertEquals("9007199254740993", client.evaluate("ak-fixture").userId());
        verify(http).post(eq(properties.accessUrl()), eq(Map.of("Authorization", "Bearer ak-fixture",
                "X-AIO-Query-Service-Key", properties.serviceKey())), eq(Map.of("dataset", "time_record")), eq(16384), eq(2));
    }

    @Test void failsClosedOnRevokedCredentialDependencyFailureAndSecondaryLock() throws Exception {
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(401, null));
        assertEquals(401, assertThrows(QueryException.class, () -> client.evaluate("session")).status());
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(503, null));
        assertEquals(503, assertThrows(QueryException.class, () -> client.evaluate("session")).status());
        when(http.post(any(), anyMap(), any(), anyInt(), anyInt())).thenReturn(new BoundedHttp.Response(200,
                new ObjectMapper().readTree("{\"rscode\":\"2001\"}")));
        assertEquals("SECONDARY_LOCK_REQUIRED", assertThrows(QueryException.class, () -> client.evaluate("session")).code());
        assertEquals(401, assertThrows(QueryException.class, () -> client.evaluate(" ")).status());
    }
}
