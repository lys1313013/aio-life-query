# Docker 打包与 GitHub Actions

沿用 `aio-life-server` 的测试 JAR → Dockerfile.runtime → 多架构镜像流程。当前自动发布对象为自有 Java 网关 `aio-life-query`；Hasura 和 MySQL Connector 保持独立镜像与配置，不能混入网关镜像。

## Docker 文件

| 文件 | 用途 |
|---|---|
| `Dockerfile` | 本地从源码构建，编译使用构建机原生平台与 Maven 缓存 |
| `Dockerfile.runtime` | CI 打包已通过测试的 JAR，避免为每个架构重新编译 |
| `.dockerignore` | 网关构建上下文只包含 `pom.xml` 和 `src` |
| `deploy/connector.Dockerfile.dockerignore` | Connector 构建上下文只包含已编译 Quarkus 运行文件 |
| `scripts/prepare_image_context.sh` | 创建仅含 `app.jar` 和 Dockerfile 的新上下文，要求来源目录只有一个 JAR |
| `scripts/smoke_image.sh` | 检查容器启动、认证、故障拒绝、非 root、审计目录及 Java PID 1 |

运行镜像使用 Java 21 JRE Alpine、Asia/Shanghai 时区、`10001:10001` 用户与组。审计目录为 `/app/audit`，挂载目录必须允许该 UID 写入。`JAVA_OPTS` 默认 `-Xms128m -Xmx512m -XX:+UseG1GC`，可由部署环境覆盖。入口使用 `exec java`，容器停止信号直接传递给 JVM。

本地源码构建：

```bash
docker build -t aio-life-query:local .
bash scripts/smoke_image.sh aio-life-query:local
```

复用已测试的 JAR 构建：

```bash
# Redis 测试需要真实 Redis；端口必须指向隔离测试实例。
AIO_QUERY_TEST_REDIS_PORT=6379 mvn -B -ntp verify -DfailIfNoTests=true
bash scripts/prepare_image_context.sh .cache/image-context
docker build -t aio-life-query:local .cache/image-context
bash scripts/smoke_image.sh aio-life-query:local
```

上下文目录要求为空；重复构建时使用新的目录。不要将整个 `.cache/`、`.env` 或审计数据作为镜像内容。

## 工作流

入口为 `.github/workflows/docker-publish.yml`。

| 触发 | 行为 |
|---|---|
| PR → `main` | JDK 21 测试、真实 Redis、生成 JAR、构建运行镜像并执行冒烟检查；不登录仓库、不推送镜像 |
| push → `main` | 上述检查成功后，发布 amd64/arm64 镜像 |
| push → `v*` 标签 | 上述检查成功后发布；版本标签使用 `v0.1.0` 等 SemVer 格式 |
| 手动运行 | 所选 ref 完成检查；只有 `main` 或 `v*` 标签执行发布 |

工作流使用三个任务：`test` → `image-check` → `build-and-push`。测试任务包含 Java 测试及 Python 元数据文件权限、原子替换和失败恢复测试；发现失败、缺少 Java 报告或 Java 跳过项时阻止发布。测试 JAR 作为 artifact 保存 1 天，Surefire 报告保存 7 天。镜像检查在 GitHub runner 的原生 amd64 架构运行；发布阶段使用 QEMU / Buildx 构建 amd64 和 arm64。

此工作流验证本网关及 Redis。涉及相邻 AIO Life 后端、Hasura、Connector 和 MySQL 的完整隔离验收仍使用 `scripts/test_e2e.sh`；不能把该工作流成功等同于完整业务链或生产部署已经验证。

发布镜像默认是 `ghcr.io/<owner>/<repository>`，名称转为小写。GHCR 使用工作流的 `GITHUB_TOKEN`，仅发布任务拥有 `packages: write` 权限，不需要另建 GHCR 密钥。

镜像标签：

- `main`：`latest`、`sha-<完整 commit SHA>`。
- `v0.1.0`：`0.1.0`、`0.1`、`sha-<完整 commit SHA>`。版本发布不移动 `latest`。
- 发布摘要记录 `ghcr.io/<owner>/<repository>@sha256:...`，生产部署优先使用此不可变引用。

实现使用与配套服务一致的 Docker Actions 主版本，以及显式文件上下文。相关行为见 [Docker Build Action](https://github.com/docker/build-push-action/tree/v6)、[Docker Metadata Action](https://github.com/docker/metadata-action/tree/v5) 与 [GitHub Token 文档](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)。

## 阿里云 ACR

在独立 `aio-life-query` GitHub 仓库 Settings → Secrets and variables → Actions 中配置以下四项 Secrets，名称与 `aio-life-server` 相同：

| Secret | 用途 |
|---|---|
| `ALIYUN_ACR_REGISTRY` | ACR 域名，不含 `https://` |
| `ALIYUN_ACR_NAMESPACE` | 命名空间 |
| `ALIYUN_ACR_USERNAME` | 登录用户名 |
| `ALIYUN_ACR_PASSWORD` | 登录密码 |

四项都未配置时仅发布 GHCR；仅配置部分时工作流明确失败。完整配置后同时发布 `<registry>/<namespace>/aio-life-query`，标签与 GHCR 相同。为兼容 ACR，沿用配套服务的 `provenance: false` 与 `sbom: false` 设置。

首次创建 GHCR Package 后，如需要匿名拉取，在 Package 设置中调整可见性；私有 Package 使用有读取权限的账号登录后拉取。仓库 Secrets 不会从相邻 `aio-life-server` 自动继承，可使用组织 Secrets 授予新仓库访问。

## Compose 使用发布镜像

`deploy/compose.yml` 同时支持本地构建与运行已有镜像：

- `AIO_QUERY_GATEWAY_IMAGE`：默认 `aio-life-query:local`，可指定 GHCR/ACR 的标签或 digest。
- `AIO_QUERY_CONNECTOR_IMAGE`：默认 `aio-life-query-mysql-connector:local`，可指定独立维护并验收的 Connector 镜像。
- `AIO_QUERY_ENGINE_IMAGE`：必须显式指定已验证的引擎镜像，原有要求保持不变。

部署主机先注入原有服务密钥、只读 JDBC 和 Redis 配置，并生成受保护元数据。Connector/Hasura 需要预先准备，网关替换示例：

```bash
export AIO_QUERY_GATEWAY_IMAGE='ghcr.io/<owner>/aio-life-query@sha256:<digest>'
docker compose -f deploy/compose.yml pull gateway
docker compose -f deploy/compose.yml up -d --no-build gateway
```

启动整套服务且全部镜像已准备时，执行 `docker compose -f deploy/compose.yml up -d --no-build`。需要本地构建时显式执行 `docker compose -f deploy/compose.yml build gateway mysql-connector`；Connector 编译产物须先通过原有脚本准备。GitHub Actions 只构建及推送镜像，没有 SSH 部署或自动修改生产服务。

当前 `aio-life-query` 为无远程地址的本地独立仓库。将配置提交并推送到对应 GitHub 仓库后才会触发 Actions；本地语法和容器验证不能替代实际 GitHub 运行结果。Hasura 生产源码镜像与 Connector 授权边界仍见主 README。
