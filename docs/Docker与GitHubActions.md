# Docker 与 GitHub Actions

## 发布对象

`.github/workflows/docker-publish.yml` 在全部检查成功后，将三个独立运行镜像发布到同一 ACR 仓库 `<registry>/<namespace>/aio-life-query`。部署主机只拉取镜像，不编译、导入本机镜像或修改容器内容。

| 组件 | `main` 浮动标签 | 每次发布的固定标签 |
| --- | --- | --- |
| Java Query 网关 | `latest` | `sha-<完整 commit SHA>` |
| 官方 Hasura v3 包装镜像 | `hasura-latest` | `hasura-sha-<完整 commit SHA>` |
| 官方 PostgreSQL Connector 包装镜像 | `connector-latest` | `connector-sha-<完整 commit SHA>` |

Hasura 和 Connector 的上游版本、固定多架构 digest、许可证见 [上游说明](../connector/UPSTREAM.md)。Hasura 保持官方二进制和入口，Connector 只增加缺少认证配置时拒绝启动的检查器。两者以 UID 10001 运行；生成的配置目录需允许 `AIO_QUERY_CONFIG_GID` 读取。业务配置和密钥不进入镜像。

生产整套更新优先填写同一 commit 的三个固定标签或 CI 摘要中的 digest。浮动标签仅在三种镜像均推送成功后更新，标签更新并非跨镜像事务。版本标签 `v*` 另外发布网关 SemVer 标签，不移动三个 `latest` 标签。

## CI 检查

PR、`main` 推送、`v*` 标签和手动运行都会检查；仅 `main` 或 `v*` 在非 PR 事件发布。

1. Java 21 `mvn verify`，使用隔离 Redis；要求存在测试报告且没有失败、错误或跳过项。运行 Python 元数据和文件权限测试，保存通过测试的 JAR。
2. 构建网关运行镜像，检查启动、审计权限、认证拒绝、Java PID 1 和非 root 用户。
3. 在原生 amd64、arm64 runner 上，从固定官方上游构建两个包装镜像，运行 `scripts/test_runtime_images.py`，验证七个数据集的完整 SQL 链、隔离、关系、金额、聚合、只读权限、数据库时限及缺少密钥拒绝启动。
4. 全部成功后，Buildx 发布三个 amd64/arm64 镜像；CI 摘要记录不可变 digest。CI 不通过 SSH 更新服务器，不替代真实用户授权和生产环境验收。

测试 JAR 保留 1 天，测试报告及隔离运行日志保留 7 天。测试 PostgreSQL 和 Redis 为虚构数据夹具；生产 Compose 不创建 Redis。

## ACR 配置

独立 Query 仓库需要四项 Actions Secrets：`ALIYUN_ACR_REGISTRY`、`ALIYUN_ACR_NAMESPACE`、`ALIYUN_ACR_USERNAME`、`ALIYUN_ACR_PASSWORD`。缺少任何一项时发布失败。复用现有 `aio-life-query` 仓库的不同组件标签，无需部署主机拥有推送凭据。

为兼容现有 ACR，发布使用 `provenance: false` 与 `sbom: false`。私有仓库拉取前，部署主机须登录具备读取权限的账号。

## 部署

推荐使用主仓库 `docker-compose-query.yml`，仅启动 `aio-life-query`、`hasura`、`postgresql-connector`，复用后端、PostgreSQL 和 Redis。独立使用本仓库时也可选 `deploy/compose.yml`，两份编排不能同时运行同名项目。

注入服务密钥、`AIO_QUERY_DATABASE_URL=postgresql://...`、已有 Redis 和配置组 ID。URI 中用户名/密码的特殊字符需 URL 编码。数据库使用专门的列级 SELECT 账号，设置只读和 5 秒时限，见 [readonly-account.sql.example](../deploy/readonly-account.sql.example)。不再使用 `AIO_QUERY_JDBC_URL`。

独立仓库启动示例：

```bash
export AIO_QUERY_CONFIG_GID="$(id -g)"
python3 scripts/build_metadata.py
docker compose -f deploy/compose.yml pull
docker compose -f deploy/compose.yml up -d --no-build
```

元数据生成器不会自动读取 `.env`，独立运行前需安全注入 Webhook 和 Connector 密钥。目录 `0750`、文件 `0640`，包含密钥，不能提交。字段、关系或内部密钥变化需重新生成配置并更新匹配的服务。仅网关端口绑定宿主回环地址；Hasura、Connector、管理端点及授权 Webhook 不作为公网入口。

在主仓库部署时，`python3 scripts/update-query-stack.py` 会检查源码与三个镜像的 commit 一致、备份并生成元数据、重建三个服务并等待健康检查。只更新网关使用 `./scripts/update-query.sh`，不执行 `down`，不更新另外两个组件。Web 直接使用其 CI 发布镜像内的 Nginx 配置，不通过 `docker cp` 或代码绑定覆盖。

## 本地验证

```bash
# 先使用隔离 Redis 完成 mvn verify，生成已测试 JAR。
AIO_QUERY_TEST_REDIS_PORT=<port> mvn -B -ntp verify -DfailIfNoTests=true
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_*.py'
bash scripts/build_engine.sh
bash scripts/build_connector.sh
python3 scripts/test_runtime_images.py
```

`deploy/engine.Dockerfile` 只继承固定的官方发行镜像并添加来源文件，不进行源码编译。Connector 构建上下文仅包含启动检查器与许可证，不包含 `.cache`、数据库配置或密钥。网关 CI 使用已测试 JAR 的最小上下文，见 `scripts/prepare_image_context.sh`、`Dockerfile.runtime`。
