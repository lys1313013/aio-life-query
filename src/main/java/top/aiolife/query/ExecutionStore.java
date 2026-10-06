package top.aiolife.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class ExecutionStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>(
            "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],60) end; return n", Long.class);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            for _,k in ipairs(KEYS) do redis.call('zremrangebyscore',k,'-inf',ARGV[1]) end
            if redis.call('zcard',KEYS[1])>=2 or redis.call('zcard',KEYS[2])>=10 then return 0 end
            for _,k in ipairs(KEYS) do redis.call('zadd',k,ARGV[2],ARGV[3]); redis.call('expire',k,60) end
            return 1
            """, Long.class);

    public ExecutionStore(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    record Ticket(String token, AccessClient.Access access, String queryId, String operationHash, String release, long expiresAt, String dataset) {}

    public void rate(String userId) {
        Long count = redis.execute(RATE, List.of("aioQuery:rate:v1:" + userId));
        if (count == null || count > 30) throw new QueryException("QUERY_LIMIT_EXCEEDED", 429, "查询过于频繁");
    }

    public Lease acquire(String userId) {
        String id = UUID.randomUUID().toString();
        List<String> keys = List.of("aioQuery:running:v1:" + userId, "aioQuery:running:v1:global");
        long now = System.currentTimeMillis();
        if (!Long.valueOf(1).equals(redis.execute(ACQUIRE, keys, Long.toString(now), Long.toString(now + 30000), id))) {
            throw new QueryException("QUERY_LIMIT_EXCEEDED", 429, "查询并发已达上限");
        }
        return new Lease(keys, id);
    }

    public final class Lease implements AutoCloseable {
        private final List<String> keys;
        private final String id;
        private Lease(List<String> keys, String id) { this.keys = keys; this.id = id; }
        public void close() { for (String key : keys) redis.opsForZSet().remove(key, id); }
    }

    public String issue(String token, AccessClient.Access access, String queryId, QueryCompiler.Plan plan) {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String ticket = "aqe_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires = Math.min(access.expiresAt(), Instant.now().getEpochSecond() + 30);
        try {
            Ticket value = new Ticket(token, access, queryId, hash(json.writeValueAsString(plan)), QueryDatasets.named(plan.dataset()).release(), expires, plan.dataset());
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(ticket), json.writeValueAsString(value),
                    Duration.ofSeconds(Math.max(1, expires - Instant.now().getEpochSecond()))))) throw QueryException.denied();
            return ticket;
        } catch (QueryException e) { throw e; }
        catch (Exception e) { throw new QueryException("AUTH_SERVICE_UNAVAILABLE", 503, "执行票据存储不可用"); }
    }

    Ticket consume(String ticket) {
        if (ticket == null || !ticket.matches("aqe_[A-Za-z0-9_-]{43}")) throw QueryException.denied();
        String raw = redis.opsForValue().getAndDelete(key(ticket));
        if (raw == null) throw QueryException.denied();
        try {
            Ticket value = json.readValue(raw, Ticket.class);
            if (!QueryDatasets.named(value.dataset()).release().equals(value.release()) || value.expiresAt() <= Instant.now().getEpochSecond()) throw QueryException.denied();
            return value;
        } catch (Exception e) { throw QueryException.denied(); }
    }

    void discard(String ticket) { redis.delete(key(ticket)); }
    private String key(String ticket) { return "aioQuery:execution:v1:" + hash(ticket); }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
