# Connector 来源与修改范围

仓库：<https://github.com/hasura/ndc-jvm-mono>

- Commit：`7ea80f966e7abfeaba56957291ff555649e21809`
- 源码归档 SHA-256：`969590bc77db018cf9d5d51be28612d37445c96a9016405e4a2e5ae5ada5de46`
- 构建环境：Gradle 8.14.3 / JDK 21；运行协议 NDC 0.1.6。
- 本仓库保存补丁与构建入口，源码及构建产物进入忽略的 `.cache/`。

补丁只保留 MySQL 构建，使用 jOOQ OSS 3.19.8。禁用 mutation、explain 和请求级连接串；SQL 查询超时 5 秒；不自动生成多语句 SET；仅投影配置中的列；修正 SMALLINT UNSIGNED 的结构映射。Connector 缺少服务密钥时拒绝访问，去掉请求正文日志，错误输出仅保留错误类别，避免 SQL 和数据进入日志。

`group_concat_max_len` 与 `max_execution_time` 通过连接配置初始化，保持 `allowMultiQueries=false`。OpenTelemetry 通过 `QUARKUS_OTEL_SDK_DISABLED=true` 在运行时关闭，见 [Quarkus 3.20 官方配置说明](https://quarkus.io/version/3.20/guides/opentelemetry/)。

分类关系扩展：仅允许受控配置中无参数的静态原生查询，将其内联为派生表，覆盖关系取数、过滤和排序的表引用；不接受客户端 SQL。MySQL 查询不生成原生查询 CTE，以兼容 5.7 的语法范围。投影同时按静态查询显式列白名单校验。`time-record-category.sql` 合并公共分类与当前用户的名称覆盖，派生集合没有独立公开查询入口。

**授权核验边界：**所选 commit 的归档未包含许可证文件，GitHub 仓库 API 的 `license` 返回 null。改用 jOOQ OSS 只解决该依赖的版本选择，不能据此认定整个 Connector 已获生产使用或再分发许可。当前用于本地验证；生产发布前需明确上游 Connector 的授权，或替换为授权明确、通过同一协议与权限验收的实现。不要将它标注为已经验证的完整免费开源发行版。

Hasura 测试镜像是固定 digest 的 `ddn-engine-local-dev`；生产源码引擎需要另行构建与同等验收，详见主 README。
