# Query 运行镜像来源

当前部署使用官方发行镜像，固定多架构 digest 见 [upstreams.lock.json](../deploy/upstreams.lock.json)。

| 组件 | 官方来源 | 固定版本 |
| --- | --- | --- |
| Hasura v3 | [hasura/graphql-engine](https://github.com/hasura/graphql-engine)、`ghcr.io/hasura/v3-engine` | `v2026.10.05` |
| PostgreSQL Connector | [hasura/ndc-postgres](https://github.com/hasura/ndc-postgres)、`ghcr.io/hasura/ndc-postgres` | `v3.1.0`，NDC `0.2.4` |

Hasura 与 Connector 的上游源码许可证为 Apache-2.0，副本和说明保存在 [licenses/](licenses/)。Hasura 包装镜像添加来源说明并以 UID 10001 运行，保持官方二进制及入口不变；入口 `ddn-engine-local-dev` 也是官方发布镜像的入口，不能单凭名称认定不适用于自托管。官方部署说明见 [Self-hosted](https://hasura.io/docs/3.0/deployment/self-hosted/)。

Connector 包装镜像增加一个 Rust 启动检查器，要求至少 32 字符的 `HASURA_SERVICE_TOKEN_SECRET` 及非空 `CONNECTION_URI`，再 `exec` 官方程序。配置使用官方 version 5 格式：显式表列白名单、固定无参数 native queries、`mutationsVersion: null`，没有动态连接串配置。Hasura 启用 `json_session_variables`，保留 Webhook 中的字符串 ID；聚合映射到 `pg_catalog.sum`，保持空集返回 null 的既有 API 契约。只读账号设置 `default_transaction_read_only=on`、`statement_timeout=5000`，仅拥有白名单列 SELECT。官方 Connector 的 explain 等内部能力不作为公网接口。

原 `ndc-jvm-mono` 派生实现和补丁已退出当前构建及部署，不再依赖缓存 Quarkus 产物。数据库连接使用标准 `postgresql://` URI，无 JDBC 部署参数。

业务派生 SQL 由本仓库维护：`time-record-category.sql` 合并当前用户的有效分类名称；`income-month.sql` 按用户、类型、自然月汇总；`exercise-record-type.sql` 与 `income-month-type.sql` 解析有效字典标签。定义来自 [relationships.json](../src/main/resources/relationships.json) 和 [datasets.json](../src/main/resources/datasets.json)，不扫描全库、不接收客户端 SQL、不创建数据库视图或外键。公共只读类型不接受个人覆盖，停用类型保留历史含义，删除、错误域及他人私有项不暴露。

CI 在 amd64/arm64 原生运行镜像验收后，发布三个 ACR 标签系列。验收包含全部七个数据集、字段和大整数、用户/软删除隔离、分类与类型关系、金额和聚合、认证拒绝、只读权限和数据库时限。隔离授权桩不是生产用户授权验收。入口及发布配置见 [Docker 与 GitHub Actions](../docs/Docker与GitHubActions.md)。
