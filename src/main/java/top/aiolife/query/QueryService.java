package top.aiolife.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

@Service
public class QueryService {
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
        var user = access.evaluate(token, "catalog"); store.rate(user.userId());
        if (name != null) {
            var dataset = QueryDatasets.named(name);
            authority(token, dataset);
            audit(user, "CATALOG_READ", dataset);
            return describe(dataset);
        }
        var available = new ArrayList<Object>();
        for (var dataset : QueryDatasets.all()) {
            try { authority(token, dataset); }
            catch (QueryException error) {
                if (error.status() == 403) continue;
                throw error;
            }
            available.add(describe(dataset));
        }
        audit.write(UUID.randomUUID().toString(), user, "CATALOG_READ", null, 0, "catalog");
        return available;
    }

    private Object describe(QueryDatasets.Dataset dataset) {
        var output = new LinkedHashMap<String, Object>();
        output.put("name", dataset.name()); output.put("domain", dataset.domain());
        output.put("description", dataset.description()); output.put("schemaVersion", dataset.release());
        output.put("policyVersion", dataset.release()); output.put("graphqlSdl", dataset.sdl());
        output.put("maxDateRangeDays", 93); output.put("maxRows", 1000);
        output.put("rangeFields", dataset.rangeFields()); output.put("queryExample", dataset.example());
        return output;
    }

    private AccessClient.Access authority(String token, QueryDatasets.Dataset dataset) {
        return "time_record".equals(dataset.name()) ? access.evaluate(token) : access.evaluate(token, dataset.name());
    }

    private void audit(AccessClient.Access user, String event, QueryDatasets.Dataset dataset) {
        audit.write(UUID.randomUUID().toString(), user, event, null, 0, dataset.name());
    }

    public Object validate(String token, QueryCompiler.Request request) {
        var dataset = QueryDatasets.domain(request.domain());
        var user = authority(token, dataset); store.rate(user.userId()); compiler.compile(request);
        audit(user, "VALIDATED", dataset);
        return Map.of("allowed", true, "dataset", dataset.name(), "releaseId", dataset.release());
    }

    public Object execute(String token, QueryCompiler.Request request) {
        // 未认证及限流拒绝不能触发同步落盘，避免绕过准入限制消耗审计资源。
        var dataset = QueryDatasets.domain(request.domain());
        var user = authority(token, dataset);
        store.rate(user.userId());
        String queryId = UUID.randomUUID().toString();
        String fingerprint = null;
        try {
            writeAudit(queryId, user, "STARTED", null, 0, dataset);
            QueryCompiler.Plan plan = compiler.compile(request);
            fingerprint = ExecutionStore.hash(plan.query());
            try (var lease = store.acquire(user.userId())) {
                String ticket = store.issue(token, user, queryId, plan);
                BoundedHttp.Response response;
                try {
                    response = http.post(properties.hasuraUrl(), Map.of("Authorization", "Bearer " + ticket),
                            Map.of("operationName", "AioQuery", "query", plan.query(), "variables", plan.variables()), 1_000_000, 10);
                } finally { store.discard(ticket); }
                var body = response.body();
                if (response.status() != 200 || body == null || body.has("errors") || !body.path("data").isObject()) {
                    throw new QueryException("UPSTREAM_QUERY_FAILED", 502, "查询执行失败，未返回部分结果");
                }
                ObjectNode data = (ObjectNode) body.path("data");
                if (data.size() != 1 || !data.has(plan.responseKey())) throw badResult();
                JsonNode result = data.path(plan.responseKey());
                int rows;
                if (dataset.root().equals(plan.root())) {
                    if (!result.isArray() || result.size() > plan.limit()) throw badResult();
                    for (JsonNode row : result) normalizeRow(row, plan);
                    rows = result.size();
                } else {
                    normalizeStats(result, plan); rows = 1;
                }
                var refreshed = authority(token, dataset);
                if (!user.sameAuthority(refreshed)) throw QueryException.denied();
                writeAudit(queryId, user, "SUCCEEDED", fingerprint, rows, dataset);
                ObjectNode output = json.createObjectNode();
                output.put("queryId", queryId).put("rowCount", rows).put("schemaVersion", dataset.release())
                        .put("policyVersion", dataset.release()).put("releaseId", dataset.release()).put("dataset", dataset.name())
                        .put("executedAt", Instant.now().toString()).set("result", data);
                output.set("dataFreshness", json.createObjectNode().put("mode", "configured-source").putNull("replicaLagMs"));
                return output;
            }
        } catch (RuntimeException error) {
            writeAudit(queryId, user, error instanceof QueryException qe ? qe.code() : "DEPENDENCY_FAILURE", fingerprint, 0, dataset);
            throw error;
        }
    }

    public Map<String, String> webhook(String ticket) {
        var execution = store.consume(ticket);
        var dataset = QueryDatasets.named(execution.dataset());
        var current = authority(execution.token(), dataset);
        if (!current.sameAuthority(execution.access())) throw QueryException.denied();
        return Map.of("x-hasura-role", dataset.role(), "x-hasura-user-id", current.userId());
    }

    private void writeAudit(String id, AccessClient.Access user, String event, String fingerprint, int rows, QueryDatasets.Dataset dataset) {
        if ("time_record".equals(dataset.name())) audit.write(id, user, event, fingerprint, rows);
        else audit.write(id, user, event, fingerprint, rows, dataset.name());
    }

    private void normalizeRow(JsonNode row, QueryCompiler.Plan plan) {
        if (!row.isObject() || row.size() != plan.selectedFields().size()) throw badResult();
        var dataset = QueryDatasets.named(plan.dataset());
        plan.selectedFields().forEach((key, name) -> {
            if (!row.has(key)) throw badResult();
            JsonNode value = row.get(key);
            var field = dataset.fields().get(name);
            if (value.isNull()) { if (!field.nullable()) throw badResult(); return; }
            switch (field.kind()) {
                case "ID" -> {
                    if (!value.isTextual() && !value.isIntegralNumber()) throw badResult();
                    ((ObjectNode) row).put(key, value.asText());
                }
                case "DECIMAL" -> {
                    ((ObjectNode) row).put(key, decimal(value));
                }
                case "INT" -> {
                    if (!value.isIntegralNumber() || !value.canConvertToInt()) throw badResult();
                }
                default -> { if (!value.isTextual()) throw badResult(); }
            }
        });
    }

    private String decimal(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) throw badResult();
        try { return new BigDecimal(value.asText()).toPlainString(); }
        catch (NumberFormatException error) { throw badResult(); }
    }

    private void normalizeStats(JsonNode result, QueryCompiler.Plan plan) {
        if (!result.isObject() || result.size() != plan.selectedFields().size()) throw badResult();
        plan.selectedFields().forEach((key, field) -> {
            JsonNode value = result.get(key);
            if (value == null) throw badResult();
            if ("count".equals(field)) {
                if (!value.isIntegralNumber() && !value.isTextual()) throw badResult();
                try { ((ObjectNode) result).put(key, new BigInteger(value.asText()).toString()); }
                catch (NumberFormatException error) { throw badResult(); }
            } else {
                if (!value.isObject() || value.size() != 1 || !value.has("sum")) throw badResult();
                JsonNode sum = value.get("sum");
                if (!sum.isNull()) {
                    if (!sum.isNumber() && !sum.isTextual()) throw badResult();
                    ((ObjectNode) value).put("sum", decimal(sum));
                }
            }
        });
    }
    private QueryException badResult() { return new QueryException("UPSTREAM_QUERY_FAILED", 502, "查询结果不符合已发布结构"); }
}
