# Codex 本地 MCP 验证

2026-10-05 使用 **Codex CLI 0.160.0** 对本地 `aio-life-query` 进行真实工具调用验收。修复初始化兼容性问题后，两个独立客户端共 **19 次 MCP 工具调用**通过预期断言。

## 环境与范围

- MCP 地址：`http://127.0.0.1:45680/mcp`，验收完成后保留本地运行环境。
- 真实链路：Codex → MCP 网关 → AIO Life 鉴权 → Hasura → MySQL Connector → MySQL；使用专用测试 MySQL / Redis 容器。
- 使用真实 Sa-Token 登录会话、虚构 A/B 用户及记录，没有读取现有开发库或生产数据。A/B 用户 ID 为相邻大整数。
- Codex 通过临时配置和环境变量注入 Bearer；未修改全局 MCP 配置。事件记录确认只调用 `aio-life-query` 工具，没有 shell 或文件操作。
- 首期范围仍为 `time_record` 只读查询。此结果不扩展原验证记录中的生产发布范围。

## 发现并修复的问题

首次连接失败：Codex initialize 请求中的 `capabilities.elicitation.form` 被 SDK 0.14.0 配合严格 Jackson 配置拒绝，返回 JSON-RPC `-32603`。此前只有空 capabilities 的握手夹具，未覆盖真实客户端能力扩展。

`QueryMcpConfiguration` 现为 MCP 协议使用独立 ObjectMapper 副本，允许未知协议能力字段；REST 和工具业务请求继续使用原严格 mapper，工具参数白名单及身份校验保留。新增 HTTP 路由回归测试覆盖扩展 initialize 能力和伪造业务 `userId` 拒绝。

## 实际客户端结果

| 场景 | 结果 |
|---|---|
| A：目录、结构、四工具 | 均实际调用成功，版本 `time-record-v1` |
| A：2026-09-01（含）至 2026-10-01（不含）明细 | 2 条，30 / 60 分钟 |
| A：同期汇总 | `count: "2"`，`sum: "90"` |
| A：变量、字段别名、limit 1 / offset 1 | 正确返回第 2 条，60 分钟 |
| A：categoryId 精确筛选 | 字符串 `"9007199254740993"` 原样筛选及返回 |
| A：2026-08-01 至 2026-09-01 空集 | `count: "0"`，`sum: null` |
| A：读取 userId、mutation 预检、122 天区间 | 均返回 `QUERY_UNSUPPORTED` |
| A：过期 schemaVersion | 返回 `SCHEMA_CHANGED` |
| B：同一时间段明细 / 汇总 | 仅 1 条、999 分钟；`count: "1"`，`sum: "999"` |
| 跨用户和逻辑删除 | A/B 均不包含对方记录，删除的 777 分钟未进入结果 |

A 共 16 次调用：目录 1、描述 1、预检 9、执行 5。B 共 3 次：描述 1、执行 2。四次预期业务拒绝在 Codex 事件中标记为 failed，属于拒绝场景验证成功。

另外直接 HTTP 复验：匿名与无效 Bearer 均为 401 / `AUTH_REQUIRED`；伪造 `X-Hasura-User-Id` 与不可信 Origin 均为 403 / `ACCESS_DENIED`。

## 构建与回归

Java 21 执行 `QueryMcpCompatibilityTest,QueryCompilerTest,QueryServiceTest,RequestBoundaryTest`，共 **30 项通过、0 失败、0 跳过**。同一份修改随后 Maven 打包成功，并在重启后的新 JAR 上完成上述 Codex 验收。本轮没有重复执行整个后端或原整套 45 项集成脚本。

## 本机运行产物

本机 `.cache/codex-local/` 保存 Codex A/B 的 `events.jsonl`、`result.md`、边界验证、测试及启动日志；配套后端 `target/query-e2e/` 保存网关日志和审计。Bearer 只保存在权限 0600 的临时凭据文件并由运行器注入环境，未进入提示词、事件及报告；缓存目录权限为 0700。运行产物不提交。

当前环境可复验或停止：

```bash
cd aio-life-query
python3 .cache/codex-local/run_codex.py A
python3 .cache/codex-local/run_codex.py B
python3 .cache/codex-local/stop.py
```

这些运行器是当前机器的临时验收产物，依赖现有编译结果及测试 classpath；不作为部署脚本。停止时仅关闭该测试后端及其网关，由启动器清理自己创建的三个容器。
