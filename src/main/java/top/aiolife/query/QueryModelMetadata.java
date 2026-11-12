package top.aiolife.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 查询逻辑模型的展示信息；查询能力仍从发布白名单派生，不暴露物理表和派生 SQL。 */
final class QueryModelMetadata {
    private static final Map<String, String> TITLES = Map.of(
            "time_record", "时迹", "exercise_record", "运动", "read_record", "阅读",
            "movie", "影视", "b_video", "视频", "performance", "演出", "income_month", "月收入");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("id", "标识"), Map.entry("date", "记录日期"), Map.entry("title", "标题"),
            Map.entry("categoryId", "分类标识"), Map.entry("durationMinutes", "时长"),
            Map.entry("startTime", "开始时间"), Map.entry("endTime", "结束时间"), Map.entry("startMinute", "开始分钟"), Map.entry("endMinute", "结束分钟"),
            Map.entry("description", "描述"), Map.entry("isManual", "手动记录"),
            Map.entry("createdAt", "创建时间"), Map.entry("updatedAt", "更新时间"),
            Map.entry("createUser", "创建人"), Map.entry("updateUser", "更新人"),
            Map.entry("userId", "所属用户"), Map.entry("isDeleted", "删除标记"),
            Map.entry("relateId", "关联标识"), Map.entry("relateType", "关联类型"),
            Map.entry("exerciseDate", "运动日期"), Map.entry("exerciseCount", "运动数量"),
            Map.entry("exerciseTypeId", "运动类型标识"), Map.entry("timeId", "时迹标识"),
            Map.entry("type", "类型"), Map.entry("author", "作者"), Map.entry("url", "链接"),
            Map.entry("status", "状态"), Map.entry("totalProgress", "总进度"),
            Map.entry("currentProgress", "当前进度"), Map.entry("finishTime", "完成时间"),
            Map.entry("remark", "备注"), Map.entry("fileId", "附件标识"),
            Map.entry("director", "导演"), Map.entry("rating", "评分"),
            Map.entry("bvid", "视频 BV 号"), Map.entry("ownerName", "UP 主"),
            Map.entry("durationSeconds", "视频时长"), Map.entry("watchedDurationSeconds", "已观看时长"),
            Map.entry("episodes", "总集数"), Map.entry("currentEpisode", "当前集数"),
            Map.entry("lastWatched", "最后观看时间"), Map.entry("cover", "封面"),
            Map.entry("notes", "笔记"), Map.entry("aid", "视频 AV 号"),
            Map.entry("pagesInfo", "分集信息"), Map.entry("coverFileId", "封面文件标识"),
            Map.entry("coverState", "封面状态"), Map.entry("coverVersion", "封面版本"),
            Map.entry("performanceName", "演出名称"), Map.entry("performer", "表演者"),
            Map.entry("performanceType", "演出类型"), Map.entry("performanceDate", "演出时间"),
            Map.entry("city", "城市"), Map.entry("venue", "场馆"), Map.entry("ticketPrice", "票价"),
            Map.entry("seatInfo", "座位"), Map.entry("review", "评价"),
            Map.entry("purchasePlatform", "购票平台"), Map.entry("orderNumber", "订单号"),
            Map.entry("incomeTypeId", "收入类型标识"), Map.entry("month", "所属月份"),
            Map.entry("monthlyAmount", "税前月收入"), Map.entry("name", "名称"));

    private QueryModelMetadata() {}

    static String title(String name) { return TITLES.getOrDefault(name, name); }

    static Map<String, Object> field(String dataset, String name, QueryDatasets.Field field,
                                    boolean sortable, List<String> aggregates, boolean distinct) {
        var info = new LinkedHashMap<String, Object>();
        info.put("label", LABELS.getOrDefault(name, name));
        info.put("type", field.apiType()); info.put("kind", field.kind());
        info.put("graphqlType", field.apiType() + ("id".equals(name) ? "!" : ""));
        info.put("nullable", field.nullable()); info.put("sortable", sortable);
        info.put("aggregateFunctions", aggregates);
        info.put("distinctFunctions", distinct ? List.of("countDistinct") : List.of());
        info.put("filterOperators", switch (field.kind()) {
            case "DATE", "DATETIME" -> List.of("_gte", "_lt");
            case "INT", "DECIMAL" -> List.of("_eq", "_gte", "_lte");
            case "JSON" -> List.of();
            default -> List.of("_eq");
        });
        if ("STATUS".equals(field.kind())) info.put("values", List.of("not_started", "in_progress", "completed", "on_hold"));
        if ("ID".equals(field.kind())) info.put("description", "字符串标识符，保留精度，不转换为数字");
        if ("time_record".equals(dataset) && Set.of("startMinute", "endMinute").contains(name)) {
            info.put("label", "startMinute".equals(name) ? "开始分钟" : "结束分钟");
            info.put("description", "当天分钟数，0=00:00，1439=23:59，闭区间端点");
            info.put("unit", "分钟");
        }
        if (name.endsWith("Minutes")) info.put("unit", "分钟");
        if (name.endsWith("Seconds")) info.put("unit", "秒");
        if (Set.of("ticketPrice", "monthlyAmount").contains(name)) info.put("unit", "元");
        if ("exerciseCount".equals(name)) info.put("description", "运动数量应按运动类型分别统计；count 是记录条数");
        if ("monthlyAmount".equals(name)) info.put("description", "按原收入类型和自然月汇总的税前金额，未减税额");
        if ("month".equals(name)) info.put("description", "自然月第一天，查询范围按整月解释");
        return info;
    }

    static Map<String, Object> relations(QueryDatasets.Dataset dataset) {
        var result = new LinkedHashMap<String, Object>();
        dataset.relations().forEach((name, relation) -> {
            var fields = new LinkedHashMap<String, Object>();
            relation.outputFields().forEach(key -> {
                var info = field(dataset.name(), key, relation.fields().get(key), false, List.of(), false);
                // 关联对象的 SDL 未声明非空字段。
                info.put("graphqlType", relation.fields().get(key).apiType());
                fields.put(key, info);
            });
            result.put(name, Map.of("typeName", relation.typeName(), "nullable", true,
                    "label", switch (name) {
                        case "category" -> "时迹分类";
                        case "exerciseType" -> "运动类型";
                        case "incomeType" -> "收入类型";
                        default -> name;
                    }, "fields", fields, "description", "删除、缺失或无权访问的关联返回 null；不提供独立根查询"));
        });
        return result;
    }
}
