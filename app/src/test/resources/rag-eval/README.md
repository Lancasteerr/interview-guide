# RAG 测评集（P1-01）

最小可复现的 RAG 基线测评：固定数据集 + 固定配置 + 独立可销毁环境，一条命令产出 JSON/Markdown 报告。

## 运行方式

前置条件：本机 Docker（PostgreSQL + pgvector、Redis）、`.env` 中已配置可用的 Embedding/Chat Provider。

```bash
# 基线（Chunk 800 + 关闭改写，rag-eval Profile 固定）
RUN_RAG_EVAL=true REDIS_DATABASE=1 ./gradlew :app:ragEvaluation --no-daemon

# 对照（开启改写，独立 run）
RUN_RAG_EVAL=true REDIS_DATABASE=1 APP_AI_RAG_REWRITE_ENABLED=true ./gradlew :app:ragEvaluation --no-daemon
```

- `RUN_RAG_EVAL`：双保险开关之一（另一层是 `rag-eval` 标签，普通 `:app:test` 不会运行本测评）。
- `REDIS_DATABASE=1`：Redis 逻辑库隔离，避免消费开发环境 Stream 消息。
- 普通测试命令 `./gradlew :app:test` 通过 `excludeTags 'rag-eval'` 排除本测评，不产生付费调用。

## 环境隔离

- 每次运行使用独立的 PostgreSQL 逻辑库 `interview_guide_rag_eval`：启动前 drop & create（含 Flyway 迁移），结束后 drop，不触碰开发库。
- 测评知识库为库内两条临时 `knowledge_bases` 记录，随库销毁。
- Redis 使用独立 database index，Stream 消息与开发环境互不可见。

## 数据 Schema（dataset-v1.jsonl，40 条）

| 字段 | 说明 |
|---|---|
| `id` | 稳定唯一；前缀对应题型：fact-/para-/ctx-/chunk-/oos- |
| `question` | 用户问题 |
| `history` | 可选多轮上下文 `{role, content}`（仅 ctx- 类使用） |
| `fixture` | 固定知识库文件名（fixtures/ 下），不写运行时 ID |
| `expectedEvidence` | 证据锚点 `[{id, text}]`；片段含任一锚点即命中，多锚点用于覆盖率 |
| `shouldReject` | 知识库外问题标记（oos- 类全部为 true） |
| `tags` | 题型分组统计键 |
| `split` | `dev`（30，调参用）/ `holdout`（10，仅终评） |
| `evaluateGeneration` | 是否执行端到端生成（固定 10 条 dev 站内样本 + 全部 8 条 oos） |

题型分布：12 直接事实 / 8 同义改写 / 6 多轮上下文 / 6 跨段 / 8 知识库外。

所有内容人工编写，无真实手机号、邮箱、姓名与公司信息。

## 指标定义

- **Hit@K**：前 K 个片段中至少出现一个期望证据锚点的样本占比。
- **EvidenceRecall@K**：命中锚点数 / 该样本全部锚点数（按证据锚点口径，非标准 IR 的 Document Recall；锚点匹配前做大小写、空白与 Markdown 归一化）。
- **MRR**：第一个命中锚点的片段排名倒数均值。
- **拒答 P/R/F1**：oos 样本答案命中拒答模板即判“拒答”，输出混淆矩阵。
- **忠实度**：固定 10 条生成样本人工复核，报告字段 `faithfulnessStatus: PASS|FAIL|UNSURE|PENDING`。
- **耗时**：P50/P95 总耗时、改写、检索、生成分阶段耗时（来自 `RagQueryExecution` 轨迹）。
- **Token**：当前链路使用 `.content()` 无法取得 usage，报告记 null 并标注；补齐属于 P1-04 范围。
- **坏例**：MRR 排名 > 3、EvidenceRecall < 1、oos 未拒答的样本逐条列出。

## 报告

- 原始输出：`app/build/reports/rag-eval/<run-id>.json` 与同名 `.md`（build 目录不入 Git）。
- 正式基线：人工复核并确认脱敏后，复制到本目录 `baselines/<run-id>.json|.md` 入 Git，与 dataset/fixture/Prompt hash、Git SHA、模型标识绑定。

## 复现注意

- 两次 run 可比的前提：同 dataset 版本、同 fixture hash、同 Prompt hash、同 Git SHA、同模型与配置（run 头部完整记录）。
- 新增盲测样本写入 `dataset-v2.jsonl`，不修改 v1。
- 前置环境不可用时任务 FAIL 并产出 `HARNESS_ERROR` 报告，不会把“没跑”误报为“通过”。
