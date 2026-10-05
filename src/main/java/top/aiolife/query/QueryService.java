package top.aiolife.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class QueryService {
    private static final Set<String> ID_FIELDS = Set.of("id", "categoryId");
    private final AccessClient access;
    private final QueryCompiler compiler;
    private final ExecutionStore store;
    private final BoundedHttp http;
    private final QueryAudit audit;
    private final QueryProperties properties;
    private final ObjectMapper json;

    public QueryService(AccessClient access, QueryCompiler compiler, ExecutionStore store, BoundedHttp http,
                        QueryAudit audit, QueryProperties properties, ObjectMapper json) {
        this.access = access; this.compiler = compiler; this.store = store; this.http = http;
        this.audit = audit; this.properties = properties; this.json = json;
    }

    public Object catalog(String token, String name) {
        AccessClient.Access user = access.evaluate(token); store.rate(user.userId());
        if (name != null && !"time_record".equals(name)) throw new QueryException("DATASET_UNAVAILABLE", 404, "数据集不可用");
        var dataset = Map.of("name", "time_record", "domain", "time", "description", "当前用户未删除的时迹；durationMinutes 单位为分钟",
                "schemaVersion", QueryProperties.RELEASE, "policyVersion", QueryProperties.RELEASE,
                "graphqlSdl", compiler.sdl(), "maxDateRangeDays", 93, "maxRows", 1000,
                "queryExample", "query Recent($start: String!, $end: String!) { timeRecords(where: {date: {_gte: $start, _lt: $end}}, limit: 100) { id date categoryId durationMinutes } }");
        audit.write(UUID.randomUUID().toString(), user, "CATALOG_READ", null, 0);
        return name == null ? List.of(dataset) : dataset;
    }

    public Object validate(String token, QueryCompiler.Request request) {
        var user = access.evaluate(token); store.rate(user.userId()); compiler.compile(request);
        audit.write(UUID.randomUUID().toString(), user, "VALIDATED", null, 0);
        return Map.of("allowed", true, "dataset", "time_record", "releaseId", QueryProperties.RELEASE);
    }

    public Object execute(String token, QueryCompiler.Request request) {
        // 未认证及限流拒绝不能触发同步落盘，避免绕过准入限制消耗审计资源。
        var user = access.evaluate(token);
        store.rate(user.userId());
        String queryId = UUID.randomUUID().toString();
        String fingerprint = null;
        try {
            audit.write(queryId, user, "STARTED", null, 0);
            QueryCompiler.Plan plan = compiler.compile(request);
            fingerprint = ExecutionStore.hash(plan.query());
            try (var lease = store.acquire(user.userId())) {
                String ticket = store.issue(token, user, queryId, plan);
                BoundedHttp.Response response;
                try {
                    response = http.post(properties.hasuraUrl(), Map.of("Authorization", "Bearer " + ticket),
                            Map.of("operationName", "AioTimeRecord", "query", plan.query(), "variables", plan.variables()), 1_000_000, 10);
                } finally { store.discard(ticket); }
                var body = response.body();
                if (response.status() != 200 || body == null || body.has("errors") || !body.path("data").isObject()) {
                    throw new QueryException("UPSTREAM_QUERY_FAILED", 502, "查询执行失败，未返回部分结果");
                }
                ObjectNode data = (ObjectNode) body.path("data");
                if (data.size() != 1 || !data.has(plan.responseKey())) throw badResult();
                JsonNode result = data.path(plan.responseKey());
                int rows;
                if ("timeRecords".equals(plan.root())) {
                    if (!result.isArray() || result.size() > plan.limit()) throw badResult();
                    for (JsonNode row : result) normalizeRow(row, plan);
                    rows = result.size();
                } else {
                    normalizeStats(result, plan); rows = 1;
                }
                var refreshed = access.evaluate(token);
                if (!user.sameAuthority(refreshed)) throw QueryException.denied();
                audit.write(queryId, user, "SUCCEEDED", fingerprint, rows);
                ObjectNode output = json.createObjectNode();
                output.put("queryId", queryId).put("rowCount", rows).put("schemaVersion", QueryProperties.RELEASE)
                        .put("policyVersion", QueryProperties.RELEASE).put("releaseId", QueryProperties.RELEASE)
                        .put("executedAt", Instant.now().toString()).set("result", data);
                output.set("dataFreshness", json.createObjectNode().put("mode", "configured-source").putNull("replicaLagMs"));
                return output;
            }
        } catch (RuntimeException error) {
            audit.write(queryId, user, error instanceof QueryException qe ? qe.code() : "DEPENDENCY_FAILURE", fingerprint, 0);
            throw error;
        }
    }

    public Map<String, String> webhook(String ticket) {
        var execution = store.consume(ticket);
        var current = access.evaluate(execution.token());
        if (!current.sameAuthority(execution.access())) throw QueryException.denied();
        return Map.of("x-hasura-role", "ai_time_reader", "x-hasura-user-id", current.userId());
    }

    private void normalizeRow(JsonNode row, QueryCompiler.Plan plan) {
        if (!row.isObject() || row.size() != plan.selectedFields().size()) throw badResult();
        plan.selectedFields().forEach((key, field) -> {
            if (!row.has(key)) throw badResult();
            JsonNode value = row.get(key);
            if (value.isNull()) { if ("id".equals(field)) throw badResult(); return; }
            if (ID_FIELDS.contains(field)) {
                if (!value.isTextual() && !value.isIntegralNumber()) throw badResult();
                ((ObjectNode) row).put(key, value.asText());
            } else if ("durationMinutes".equals(field)) {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) throw badResult();
            } else if (!value.isTextual()) throw badResult();
        });
    }

    private void normalizeStats(JsonNode result, QueryCompiler.Plan plan) {
        if (!result.isObject() || result.size() != plan.selectedFields().size()) throw badResult();
        plan.selectedFields().forEach((key, field) -> {
            JsonNode value = result.get(key);
            if (value == null) throw badResult();
            if ("count".equals(field)) {
                if (!value.isIntegralNumber() && !value.isTextual()) throw badResult();
                ((ObjectNode) result).put(key, value.asText());
            } else {
                if (!value.isObject() || value.size() != 1 || !value.has("sum")) throw badResult();
                JsonNode sum = value.get("sum");
                if (!sum.isNull()) {
                    if (!sum.isNumber() && !sum.isTextual()) throw badResult();
                    ((ObjectNode) value).put("sum", sum.asText());
                }
            }
        });
    }
    private QueryException badResult() { return new QueryException("UPSTREAM_QUERY_FAILED", 502, "查询结果不符合已发布结构"); }
}
