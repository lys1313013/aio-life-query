# Connector 来源与修改范围

文档核对日期：2026-10-18。按本仓库固定构建脚本、补丁、关系 SQL 与元数据生成器核对；不表示重新核验了上游最新版本或许可。

仓库：[hasura/ndc-jvm-mono](https://github.com/hasura/ndc-jvm-mono)。

- Commit：`7ea80f966e7abfeaba56957291ff555649e21809`
- 源码归档 SHA-256：`969590bc77db018cf9d5d51be28612d37445c96a9016405e4a2e5ae5ada5de46`
- 构建环境：Gradle 8.14.3 / JDK 21；运行协议 NDC 0.1.6。
- 本仓库保存补丁与构建入口，源码及构建产物进入忽略的 `.cache/`。

补丁只保留 MySQL 构建，使用 jOOQ OSS 3.19.8。禁用 mutation、explain 和请求级连接串；SQL 查询超时 5 秒；不自动生成多语句 SET；仅投影配置中的列；修正 SMALLINT UNSIGNED 的结构映射。Connector 缺少服务密钥时拒绝访问，去掉请求正文日志，错误输出仅保留错误类别，避免 SQL 和数据进入日志。

`group_concat_max_len` 与 `max_execution_time` 通过连接配置初始化，保持 `allowMultiQueries=false`。OpenTelemetry 通过 `QUARKUS_OTEL_SDK_DISABLED=true` 在运行时关闭，见 [Quarkus 3.20 官方配置说明](https://quarkus.io/version/3.20/guides/opentelemetry/)。

分类关系扩展：仅允许受控配置中无参数的静态原生查询，将其内联为派生表，覆盖关系取数、过滤和排序的表引用；不接受客户端 SQL。MySQL 查询不生成原生查询 CTE，以兼容 5.7 的语法范围。投影同时按静态查询显式列白名单校验。`time-record-category.sql` 合并公共分类与当前用户的名称覆盖，派生集合没有独立公开查询入口。

关系定义来自 [`relationships.json`](../src/main/resources/relationships.json)，派生 SQL 为 [`time-record-category.sql`](time-record-category.sql)。通过时迹 ID 关联，每条时迹最多一个分类；只公开 `id`、`name`，内部 `recordId` 用于 Hasura 映射，`userId` 用于行过滤。公共分类保留公共 ID，名称优先取当前用户未删除、ID 最大的覆盖；停用分类仍用于历史记录，删除、缺失或其他用户私有分类不返回。名称精确筛选可匹配多个同名分类。

六个主数据集的 111 个物理字段来自 [`datasets.json`](../src/main/resources/datasets.json)，由元数据生成器显式投影；不扫描或公开全库。分类额外需要 `time_tracker_category` 的 `id`、`user_id`、`template_id`、`name`、`is_deleted` 五列 SELECT，完整授权见 [`readonly-account.sql.example`](../deploy/readonly-account.sql.example)。

## 构建与验证

在本仓库根目录执行 `bash scripts/build_connector.sh`，输出为 `.cache/connector/quarkus-app/`。脚本校验归档 SHA-256，并记录补丁 SHA-256；已有源码缓存与当前补丁不一致时直接失败，需先移走 `.cache/connector-source` 再重建。构建使用 `-x test`，成功只表示打包完成。

准备网关 JAR 后执行 `python3 scripts/test_category_relationship.py`，验证实际 GraphQL → Hasura → Connector → MySQL 关系查询，授权服务使用本地桩；完整 AIO Life 授权链使用 `bash scripts/test_e2e.sh`。MySQL 5.7 镜像及平台参数见 [README](../README.md#按分类名称查询时迹)。脚本存在不代表本轮已执行，历史结果及未覆盖条件见 [验证记录](../docs/验证记录.md)。

## 授权与引擎边界

**授权核验边界：**此前核验所选 commit 的归档未包含许可证文件，当时 GitHub 仓库 API 的 `license` 返回 null；本次未重新查询上游。改用 jOOQ OSS 只解决该依赖的版本选择，不能据此认定整个 Connector 已获生产使用或再分发许可。当前用于本地验证；生产发布前需明确上游 Connector 的授权，或替换为授权明确、通过同一协议与权限验收的实现。不要将它标注为已经验证的完整免费开源发行版。

Hasura 测试镜像是固定 digest 的 `ddn-engine-local-dev`；生产源码引擎需要另行构建与同等验收，详见 [README](../README.md#独立部署)。GitHub Actions 仅发布 Java 网关，不构建或发布本 Connector 和 Hasura 引擎，详见 [Docker 与 GitHub Actions](../docs/Docker与GitHubActions.md)。
