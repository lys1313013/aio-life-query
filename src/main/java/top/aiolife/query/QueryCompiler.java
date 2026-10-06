package top.aiolife.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.AstPrinter;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.EnumValue;
import graphql.language.Field;
import graphql.language.IntValue;
import graphql.language.Node;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
import graphql.language.OperationDefinition;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.Validator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 首期采用显式 GraphQL 子集；未知语法拒绝，不将原始操作直接透传。 */
@Component
public class QueryCompiler {
    private final ObjectMapper json;
    private final Map<String, GraphQLSchema> schemas = new HashMap<>();
    public record Request(String domain, String schemaVersion, String policyVersion,
                          String operationName, String query, ObjectNode variables) {}
    public record Plan(String query, ObjectNode variables, String responseKey, String root,
                       Map<String, String> selectedFields, int limit, String dataset) {}

    public QueryCompiler(ObjectMapper json) throws Exception {
        this.json = json;
        for (var dataset : QueryDatasets.all()) {
            schemas.put(dataset.name(), new SchemaGenerator().makeExecutableSchema(
                    new SchemaParser().parse(dataset.sdl()), RuntimeWiring.newRuntimeWiring().build()));
        }
        ParserOptions.setDefaultParserOptions(ParserOptions.newParserOptions().maxCharacters(32768)
                .maxTokens(2000).maxWhitespaceTokens(4000).maxRuleDepth(32).build());
    }

    public String sdl() { return QueryDatasets.named("time_record").sdl(); }

    public Plan compile(Request request) {
        if (request == null || request.query() == null
                || request.query().getBytes(StandardCharsets.UTF_8).length > 32768) throw QueryException.invalid();
        var dataset = QueryDatasets.domain(request.domain());
        if (!dataset.release().equals(request.schemaVersion()) || !dataset.release().equals(request.policyVersion())) {
            throw new QueryException("SCHEMA_CHANGED", 409, "请重新获取数据集目录");
        }
        try {
            Document document = Parser.parse(request.query());
            if (document.getDefinitions().size() != 1 || !(document.getDefinitions().getFirst() instanceof OperationDefinition op)
                    || op.getOperation() != OperationDefinition.Operation.QUERY || op.getName() == null
                    || !op.getName().equals(request.operationName()) || !op.getDirectives().isEmpty()) throw QueryException.invalid();
            checkNodes(document, 0, new int[]{0});
            if (!new Validator().validateDocument(schemas.get(dataset.name()), document, Locale.ROOT).isEmpty()) throw QueryException.invalid();
            if (op.getSelectionSet().getSelections().size() != 1
                    || !(op.getSelectionSet().getSelections().getFirst() instanceof Field root)) throw QueryException.invalid();
            String name = root.getName();
            if (!Set.of(dataset.root(), dataset.statsRoot()).contains(name)) throw QueryException.invalid();
            ObjectNode variables = request.variables() == null ? json.createObjectNode() : request.variables().deepCopy();
            Set<String> declared = new HashSet<>();
            for (var def : op.getVariableDefinitions()) {
                declared.add(def.getName());
                if (!variables.has(def.getName()) && def.getDefaultValue() != null) variables.set(def.getName(), value(def.getDefaultValue(), variables));
            }
            variables.fieldNames().forEachRemaining(key -> { if (!declared.contains(key)) throw QueryException.invalid(); });
            Map<String, JsonNode> args = new HashMap<>();
            for (Argument argument : root.getArguments()) {
                if (args.put(argument.getName(), value(argument.getValue(), variables)) != null) throw QueryException.invalid();
            }
            JsonNode where = args.get("where");
            requireObject(where, dataset.fields().keySet());
            boolean bounded = false;
            for (var entry : dataset.fields().entrySet()) {
                String fieldName = entry.getKey();
                if (!where.has(fieldName)) continue;
                var field = entry.getValue();
                JsonNode comparison = where.get(fieldName);
                if (Set.of("DATE", "DATETIME").contains(field.kind())) {
                    requireObject(comparison, Set.of("_gte", "_lt"));
                    LocalDateTime start = dateTime(comparison.get("_gte")), end = dateTime(comparison.get("_lt"));
                    long seconds = java.time.Duration.between(start, end).getSeconds();
                    if (seconds <= 0 || seconds > 93L * 86400) throw QueryException.invalid();
                    if ("DATE".equals(field.kind())) {
                        LocalDate.parse(text(comparison.get("_gte"), 10));
                        LocalDate.parse(text(comparison.get("_lt"), 10));
                    } else {
                        ((ObjectNode) comparison).put("_gte", start.toString()).put("_lt", end.toString());
                    }
                    bounded |= dataset.rangeFields().contains(fieldName);
                } else if ("INT".equals(field.kind())) {
                    requireObject(comparison, Set.of("_eq", "_gte", "_lte"));
                    int max = "time_record".equals(dataset.name()) ? 1440 : Integer.MAX_VALUE;
                    comparison.elements().forEachRemaining(v -> integer(v, 0, max));
                } else if ("DECIMAL".equals(field.kind())) {
                    requireObject(comparison, Set.of("_eq", "_gte", "_lte"));
                    comparison.elements().forEachRemaining(v -> {
                        var number = new BigDecimal(text(v, 32));
                        if (number.signum() < 0 || number.scale() > 2 || number.compareTo(new BigDecimal("99999999.99")) > 0) throw QueryException.invalid();
                    });
                } else {
                    requireObject(comparison, Set.of("_eq"));
                    String literal = text(comparison.get("_eq"), "ID".equals(field.kind()) ? 64 : 1000);
                    if ("STATUS".equals(field.kind()) && !Set.of("not_started", "in_progress", "completed", "on_hold").contains(literal)) throw QueryException.invalid();
                }
            }
            if (!bounded) throw QueryException.invalid();
            ObjectNode normalized = json.createObjectNode().set("where", where);
            int limit = 1;
            String definitions = "$where: " + dataset.boolType() + "!";
            String arguments = "where: $where";
            if (dataset.root().equals(name)) {
                limit = args.containsKey("limit") ? integer(args.get("limit"), 1, 1000) : 100;
                int offset = args.containsKey("offset") ? integer(args.get("offset"), 0, 10000) : 0;
                ArrayNode order = json.createArrayNode();
                if (args.containsKey("orderBy")) {
                    JsonNode input = args.get("orderBy");
                    if (input.isObject()) input = json.createArrayNode().add(input);
                    if (!input.isArray() || input.size() > 3) throw QueryException.invalid();
                    for (var item : input) {
                        requireObject(item, Set.copyOf(dataset.orderFields()));
                        if (item.size() != 1 || !Set.of("Asc", "Desc").contains(item.elements().next().asText())) throw QueryException.invalid();
                        order.add(item);
                    }
                }
                if (order.isEmpty()) order.addObject().put(dataset.rangeFields().getFirst(), "Desc");
                boolean hasId = false;
                for (var item : order) if (item.has("id")) hasId = true;
                if (!hasId) order.addObject().put("id", "Asc");
                normalized.put("limit", limit).put("offset", offset).set("orderBy", order);
                definitions += ", $limit: Int!, $offset: Int!, $orderBy: [" + dataset.orderType() + "!]!";
                arguments += ", limit: $limit, offset: $offset, orderBy: $orderBy";
            } else {
                arguments = "filter: {where: $where}";
            }
            Map<String, String> selected = new LinkedHashMap<>();
            for (var selection : root.getSelectionSet().getSelections()) {
                if (!(selection instanceof Field field) || field.getName().startsWith("__")) throw QueryException.invalid();
                String key = field.getAlias() == null ? field.getName() : field.getAlias();
                if (selected.put(key, field.getName()) != null) throw QueryException.invalid();
                if (field.getSelectionSet() != null) {
                    for (var nested : field.getSelectionSet().getSelections()) {
                        if (!(nested instanceof Field nf) || !"sum".equals(nf.getName()) || nf.getAlias() != null) throw QueryException.invalid();
                    }
                }
            }
            if (selected.size() > dataset.fields().size()) throw QueryException.invalid();
            String key = root.getAlias() == null ? name : root.getAlias();
            String query = "query AioQuery(" + definitions + ") { " + key + ": " + name + "(" + arguments + ") "
                    + AstPrinter.printAst(root.getSelectionSet()) + " }";
            return new Plan(query, normalized, key, name, Map.copyOf(selected), limit, dataset.name());
        } catch (QueryException e) { throw e; }
        catch (RuntimeException e) { throw QueryException.invalid(); }
    }

    private LocalDateTime dateTime(JsonNode value) {
        String text = text(value, 19);
        if (text.length() == 10) return LocalDate.parse(text).atStartOfDay();
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private void checkNodes(Node<?> node, int depth, int[] count) {
        if (depth > 20 || ++count[0] > 1000 || node instanceof Directive) throw QueryException.invalid();
        for (Node<?> child : node.getChildren()) checkNodes(child, depth + 1, count);
    }

    private JsonNode value(Value<?> value, ObjectNode variables) {
        if (value instanceof VariableReference v) return variables.path(v.getName());
        if (value instanceof StringValue v) return JsonNodeFactory.instance.textNode(v.getValue());
        if (value instanceof IntValue v) return JsonNodeFactory.instance.numberNode(v.getValue());
        if (value instanceof EnumValue v) return JsonNodeFactory.instance.textNode(v.getName());
        if (value instanceof NullValue) return JsonNodeFactory.instance.nullNode();
        if (value instanceof ArrayValue v) {
            ArrayNode result = json.createArrayNode(); v.getValues().forEach(item -> result.add(value(item, variables))); return result;
        }
        if (value instanceof ObjectValue v) {
            ObjectNode result = json.createObjectNode();
            v.getObjectFields().forEach(field -> { if (result.has(field.getName())) throw QueryException.invalid(); result.set(field.getName(), value(field.getValue(), variables)); });
            return result;
        }
        throw QueryException.invalid();
    }

    private void requireObject(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject() || value.isEmpty()) throw QueryException.invalid();
        value.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw QueryException.invalid(); });
    }
    private String text(JsonNode value, int max) {
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) throw QueryException.invalid();
        return value.textValue();
    }
    private int integer(JsonNode value, int min, int max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) throw QueryException.invalid();
        return value.intValue();
    }
}
