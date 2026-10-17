package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AIO_QUERY_TEST_REDIS_PORT", matches = "[0-9]+")
class ExecutionStoreIntegrationTest {
    private LettuceConnectionFactory connection;
    private ExecutionStore store;
    private final String userId = UUID.randomUUID().toString();

    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("AIO_QUERY_TEST_REDIS_PORT"))));
        connection.afterPropertiesSet(); connection.start();
        store = new ExecutionStore(new StringRedisTemplate(connection), new ObjectMapper());
    }
    @AfterEach void close() { connection.destroy(); }

    @Test void ticketIsSingleUseAndExpiredAuthorityFailsClosed() {
        var json = new ObjectMapper();
        var plan = new QueryCompiler.Plan("query fixture", json.createObjectNode(), "timeRecords", "timeRecords", Map.of("id", "id"), 1, "time_record");
        var access = new AccessClient.Access(userId, "aio-life-query", "fixture", "login", Instant.now().getEpochSecond() + 60);
        String ticket = store.issue(access, "fixture", plan);
        assertEquals(access, store.consume(ticket).access());
        assertThrows(QueryException.class, () -> store.consume(ticket));
        var expired = new AccessClient.Access(userId, "aio-life-query", "fixture", "login", 1);
        String expiredTicket = store.issue(expired, "fixture", plan);
        assertThrows(QueryException.class, () -> store.consume(expiredTicket));
    }

    @Test void leasesEnforceUserAndGlobalConcurrencyAndRelease() {
        try (var first = store.acquire(userId); var second = store.acquire(userId)) {
            assertThrows(QueryException.class, () -> store.acquire(userId));
        }
        var leases = new ArrayList<ExecutionStore.Lease>();
        try {
            for (int i = 0; i < 10; i++) leases.add(store.acquire(userId + i));
            assertThrows(QueryException.class, () -> store.acquire(userId));
        } finally { leases.forEach(ExecutionStore.Lease::close); }
        try (var available = store.acquire(userId)) { assertNotNull(available); }
    }

    @Test void ticketStoresOnlyAuthorizedIdentityAndRejectsStaleReleaseAndForgery() throws Exception {
        var json = new ObjectMapper();
        var plan = new QueryCompiler.Plan("query fixture", json.createObjectNode(), "timeRecords", "timeRecords", Map.of("id", "id"), 1, "time_record");
        var access = new AccessClient.Access(userId, "aio-life-query", "fixture", "login", Instant.now().getEpochSecond() + 60);
        String ticket = store.issue(access, "fixture", plan);
        var redis = new StringRedisTemplate(connection);
        String key = "aioQuery:execution:v2:" + ExecutionStore.hash(ticket);
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(redis.opsForValue().get(key));
        assertFalse(snapshot.has("token"));
        assertEquals(userId, snapshot.at("/access/userId").asText());
        assertEquals("time_record", snapshot.path("dataset").asText());
        snapshot.put("release", "obsolete-release");
        redis.opsForValue().set(key, json.writeValueAsString(snapshot), java.time.Duration.ofSeconds(30));
        assertThrows(QueryException.class, () -> store.consume(ticket));
        assertNull(redis.opsForValue().get(key));
        assertThrows(QueryException.class, () -> store.consume("aqe_" + "x".repeat(43)));
    }
}
