# RAG 测评集（P1-01）

最小可复现的 RAG 基线测评：固定数据集 + 固定配置 + 独立可销毁环境，一条命令产出 JSON/Markdown 报告。

## 运行方式

前置条件：本机 Docker（PostgreSQL + pgvector、Redis）、`.env` 中已配置可用的 Embedding/Chat Provider。

```bash
# 基线：显式关闭 rerank（Chunk 800 + 关闭改写，rag-eval Profile 固定）
RUN_RAG_EVAL=true RAG_EVAL_MODE=baseline ./gradlew :app:ragEvaluation --no-daemon

# 单臂 rerank：显式强制调用已配置的 rerank Provider
RUN_RAG_EVAL=true RAG_EVAL_MODE=rerank ./gradlew :app:ragEvaluation --no-daemon

# 配对对照：一次运行同时执行 vector-only 与 vector+rerank
RUN_RAG_EVAL=true RAG_EVAL_MODE=paired-rerank \
  APP_AI_RAG_REWRITE_ENABLED=false ./gradlew :app:ragEvaluation --no-daemon

# 独立 Query 改写实验（仍是单臂，不属于 paired-rerank）
RUN_RAG_EVAL=true APP_AI_RAG_REWRITE_ENABLED=true ./gradlew :app:ragEvaluation --no-daemon
```

`RAG_EVAL_MODE` 未设置时默认为 `baseline`。`baseline` 使用 `DISABLED`，`rerank` 使用
`FORCE_ENABLED`；两者都不修改 Spring 单例中的全局开关。旧命令仍可运行，但建议显式传模式。

`paired-rerank` 对每个样本使用相同知识库、问题、历史消息和检索参数，分别执行 vector-only 与
vector+rerank 的 `retrieveOnly()`，不重复生成答案。它只测量当前向量 Top-K 候选内的重排效果，
不会扩大候选池。报告包含两个实验臂、逐样本配对结果、指标 delta、胜负平、候选集合/Query 上下文
一致性和 rerank 状态分布。

配对模式要求 Query 改写关闭；显式设置 `APP_AI_RAG_REWRITE_ENABLED=true` 会直接拒绝启动。候选
集合不一致、Query 上下文不一致、rerank 回退、未配置、模型不支持或 HTTP 错误都会生成带原因的
无效报告并使任务失败；`INSUFFICIENT_CANDIDATES` 是合法跳过，但会计数。无效配对不会产出正式
“rerank 有效/无效”结论。配对报告固定标记 `generationEvaluation=false`、
`rejectionEvaluation=NOT_EVALUATED`。

报告会额外记录 Rerank 状态、原因、分数与 P50/P95；环境快照只保存模型和 instruction hash，
不会保存 API Key、Workspace ID 或完整调用地址。

- `RUN_RAG_EVAL`：双保险开关之一（另一层是 `rag-eval` 标签，普通 `:app:test` 不会运行本测评）。
- `RAG_EVAL_MODE`：`baseline`、`rerank` 或 `paired-rerank`，默认 `baseline`。
- Redis 隔离：`rag-eval` Profile 默认使用 database 1（可用 `REDIS_DATABASE` 覆盖），显式设为 0 会被测评启动守卫拒绝；task 会自动加载根目录 `.env`（含 `APP_AI_CONFIG_ENCRYPTION_KEY` 等）。
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

题型分布：12 直接事实 / 8 同义改写 / 6 多轮上下文 / 6 跨段 / 8 知识库外；dev 30 条 / holdout 10 条。语料每篇 fixture 切分后 Chunk 数大于最大 Top K（20），跨段样本锚点经 `RagEvalCorpusTest` 校验确实落在不同 Chunk。

所有内容人工编写，无真实手机号、邮箱、姓名与公司信息。

## 指标定义

- **Hit@K**：前 K 个片段中至少出现一个期望证据锚点的样本占比。
- **EvidenceRecall@K**：命中锚点数 / 该样本全部锚点数（按证据锚点口径，非标准 IR 的 Document Recall；锚点匹配前做大小写、空白与 Markdown 归一化）。
- **MRR**：第一个命中锚点的片段排名倒数均值。
- **拒答 P/R/F1**：oos 样本答案命中拒答模板即判“拒答”，输出混淆矩阵。
- **忠实度**：固定 10 条生成样本人工复核，报告字段 `faithfulnessStatus: PASS|FAIL|UNSURE|PENDING`。
- **耗时**：P50/P95 总耗时、改写、检索、生成分阶段耗时（来自 `RagQueryExecution` 轨迹）。
- **配对 delta**：统一为 `vector+rerank - vector-only`；首命中排名按排名越小越好单独统计胜负平，
  命中改善、命中退化和持平不通过简单数值相减掩盖。
- **Token**：当前链路使用 `.content()` 无法取得 usage，报告记 null 并标注；补齐属于 P1-04 范围。
- **坏例**：MRR 排名 > 3、EvidenceRecall < 1、oos 未拒答的样本逐条列出。

## 报告

- 原始输出：`app/build/reports/rag-eval/<run-id>.json` 与同名 `.md`（build 目录不入 Git）。
- 配对报告：`evaluationMode=paired-rerank`，`arms.vectorOnly` 与 `arms.vectorRerank` 保存两臂指标
  和样本，`comparison` 保存差值、胜负平、一致性和状态计数，顶层 `samples` 保存逐样本配对明细。
- 正式基线：人工复核并确认脱敏后，复制到本目录 `baselines/<run-id>.json|.md` 入 Git，与 dataset/fixture/Prompt hash、Git SHA、模型标识绑定。

## 复现注意

- 单臂 run 可比的前提：同 dataset 版本、同 fixture hash、同 Prompt hash、同 Git SHA、同模型与配置（run 头部完整记录）。
- 配对 run 额外要求两臂候选文档集合一致，仅允许排序不同；任务失败时先查看 JSON/Markdown 中的 `pairErrors`、
  `comparisonStatus` 和 `rerankStatusCounts`。
- 新增盲测样本写入 `dataset-v2.jsonl`，不修改 v1。
- 前置环境不可用时任务 FAIL 并产出 `HARNESS_ERROR` 报告，不会把“没跑”误报为“通过”。
