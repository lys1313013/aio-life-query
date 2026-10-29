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
import java.util.List;
import java.util.Set;
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
            available.add(Map.of("name", dataset.name(), "description", dataset.description(),
                    "capabilities", List.of("details", "aggregate")));
        }
        audit.write(UUID.randomUUID().toString(), user, "CATALOG_READ", null, 0, "catalog");
        return available;
    }

    private Object describe(QueryDatasets.Dataset dataset) {
        var output = new LinkedHashMap<String, Object>();
        output.put("name", dataset.name()); output.put("domain", dataset.domain());
        output.put("description", dataset.description()); output.put("releaseId", dataset.release());
        output.put("graphqlSdl", dataset.sdl());
        output.put("maxDateRangeDays", QueryProperties.MAX_DATE_RANGE_DAYS); output.put("maxRows", 1000);
        output.put("rangeFields", dataset.rangeFields()); output.put("queryExample", dataset.example());
        output.put("limits", Map.of("maxOffset", 10000, "defaultLimit", 100, "defaultOffset", 0,
                "maxOrderBy", 3, "maxRoots", 1, "maxOperations", 1, "maxQueryBytes", 32768));
        output.put("unsupported", List.of("fragment", "directive", "introspection", "mutation",
                "subscription", "OR", "groupBy", "fuzzySearch"));
        output.put("rangeSemantics", "rangeFields 中至少一个字段必须有 _gte（含）/_lt（不含）；日期 YYYY-MM-DD，本地时间不带时区，最长 366 天");
        output.put("defaultOrder", dataset.rangeFields().getFirst() + " Desc, id Asc");
        var fields = new LinkedHashMap<String, Object>();
        dataset.fields().forEach((name, field) -> {
            var info = new LinkedHashMap<String, Object>();
            info.put("type", field.apiType()); info.put("kind", field.kind());
            info.put("filterOperators", switch (field.kind()) {
                case "DATE", "DATETIME" -> List.of("_gte", "_lt");
                case "INT", "DECIMAL" -> List.of("_eq", "_gte", "_lte");
                case "JSON" -> List.of();
                default -> List.of("_eq");
            });
            if ("STATUS".equals(field.kind())) info.put("values", List.of("not_started", "in_progress", "completed", "on_hold"));
            if ("ID".equals(field.kind())) info.put("description", "字符串标识符，保留精度，不转换为数字");
            if ("time_record".equals(dataset.name()) && Set.of("startTime", "endTime").contains(name))
                info.put("description", "当天分钟数，0=00:00，1439=23:59，闭区间端点");
            if (name.equals("durationMinutes")) info.put("unit", "分钟");
            if (name.equals("ticketPrice")) info.put("unit", "元");
            fields.put(name, info);
        });
        output.put("fields", fields);
        return output;
    }

    private AccessClient.Access authority(String token, QueryDatasets.Dataset dataset) {
        return "time_record".equals(dataset.name()) ? access.evaluate(token) : access.evaluate(token, dataset.name());
    }

    private void audit(AccessClient.Access user, String event, QueryDatasets.Dataset dataset) {
        audit.write(UUID.randomUUID().toString(), user, event, null, 0, dataset.name());
    }

    public Object validate(String token, QueryCompiler.QueryRequest request) {
        return validate(token, compiler.resolve(request));
    }

    public Object execute(String token, QueryCompiler.QueryRequest request) {
        return execute(token, compiler.resolve(request));
    }

    Object validate(String token, QueryCompiler.Request request) {
        var dataset = QueryDatasets.domain(request.domain());
        var user = authority(token, dataset); store.rate(user.userId()); compiler.compile(request);
        audit(user, "VALIDATED", dataset);
        return Map.of("allowed", true, "dataset", dataset.name(), "releaseId", dataset.release());
    }

    public Object executeGraphql(String token, QueryCompiler.GraphqlRequest request) {
        // 仅解析有大小/深度限制的文档以确定数据集；执行前统一完成一次用户鉴权。
        ObjectNode result = json.valueToTree(execute(token, compiler.graphqlRequest(request)));
        JsonNode data = result.remove("result");
        return Map.of("data", data, "extensions", result);
    }

    Object execute(String token, QueryCompiler.Request request) {
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
                String ticket = store.issue(user, queryId, plan);
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
        // 票据保存入口鉴权结果；消费时检查有效期、发布版本和一次性使用，不再请求用户鉴权。
        return Map.of("x-hasura-role", dataset.role(), "x-hasura-user-id", execution.access().userId());
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
            var relation = dataset.relations().get(name);
            if (relation != null) {
                if (value.isNull()) return;
                var selection = plan.relationSelections().get(key);
                if (selection == null) throw badResult();
                normalizeFields(value, selection, relation.fields());
                return;
            }
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
                case "JSON" -> { /* 保留 JSON 对象、数组和标量，不转换成字符串。 */ }
                default -> { if (!value.isTextual()) throw badResult(); }
            }
        });
    }

    private void normalizeFields(JsonNode row, Map<String, String> selected, Map<String, QueryDatasets.Field> fields) {
        if (!row.isObject() || row.size() != selected.size()) throw badResult();
        selected.forEach((key, name) -> {
            JsonNode value = row.get(key);
            var field = fields.get(name);
            if (value == null || (value.isNull() && !field.nullable())) throw badResult();
            if (value.isNull()) return;
            if ("ID".equals(field.kind())) {
                if (!value.isTextual() && !value.isIntegralNumber()) throw badResult();
                ((ObjectNode) row).put(key, value.asText());
            } else if (!value.isTextual()) throw badResult();
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
