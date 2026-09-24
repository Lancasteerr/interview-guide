# 后端服务职责边界

本文记录后端 Service 的目录约定、主要调用链和兼容门面。重构时优先保持门面接口稳定，再把实现职责下沉到同一领域内的专用组件。

## 目录规则

业务模块遵循 `Controller -> Service -> Repository`。Controller 只负责路由、参数校验和委托；Service 负责业务编排；Repository 负责持久化查询。

`knowledgebase/service` 按职责分为：

- `management`：知识库上传、删除、列表、统计、解析、持久化。
- `rag`：查询编排、Query Rewrite、向量召回、Rerank、聊天会话和查询响应。
- `question`：题目查询、题目生成、生成状态和持久化。
- `interview`：知识库面试相关流程。

新增知识库 Service 时，先判断它处理的是资源管理、RAG 检索、题目流程还是面试流程，再放入对应目录；不要按“一个接口一个目录”拆分，也不要把外部系统适配代码放入业务 Service。

## RAG 调用链

```text
KnowledgeBaseQueryService（兼容门面）
  -> KnowledgeBaseRagPromptService
       -> Query Rewrite / Prompt 组装 / 搜索参数
  -> KnowledgeBaseRagRetrievalService
       -> 向量召回 / 候选合并 / Rerank / 命中结果
  -> KnowledgeBaseRagResponseService
       -> 同步响应归一化 / 流式响应探测窗口
  -> RagMetrics
       -> 请求、阶段耗时和拒答指标
```

`retrieveOnly` 与生产查询共享改写、召回和 Rerank 链路。`RerankExecutionMode` 只由评测或实验入口显式指定，生产入口继续使用全局配置。

## 语音面试调用链

```text
WebSocket Handler
  -> SessionRegistry / AsrCoordinator
  -> VoiceWebSocketTurnService
       -> DashscopeLlmService
       -> VoiceWebSocketTtsChunkEmitter / QwenTtsService
       -> MessageService / ConversationService
  -> VoiceInterviewService（HTTP 与生命周期兼容门面）
       -> SessionLifecycle / Message / EvaluationLifecycle
```

Handler 保留 WebSocket 协议入口、连接清理、控制消息和状态调度；LLM/TTS 回合不应重新放回 Handler。TTS 分片必须保持句子序号顺序，失败时仍使用完整文本 TTS 兜底。

## 配置与基础设施边界

- `LlmProviderConfigService`：对外 Provider 配置 API 的兼容门面。
- `LlmProviderConfigFileService`：YAML、`.env` 文本持久化与脱敏。
- `LlmProviderConfigValidator`：Embedding、Rerank 规则校验。
- `LlmProviderDefaultService`：默认 Chat/Embedding Provider 管理。
- `VoiceProviderConfigService`：ASR/TTS 配置读写与刷新。
- `LlmProviderRegistry` 及其子组件：Provider 解析、客户端创建/缓存和默认降级。
- `RedisService`：保留旧调用方 API 的兼容门面；具体能力在 KV/Hash、Lock/Stream、Collection 适配器中实现。
- `FileStorageService`：保留简历/知识库语义 API 的兼容门面；S3 协议由 `S3ObjectStorageService` 处理，Key 由 `FileStorageKeyService` 生成。
- `PdfExportService`：保留导出 API 的兼容门面；公共字体排版、简历分析和面试报告分别由专用组件处理。

兼容门面可以存在，但新增业务逻辑应放到专用组件，不应继续扩大门面文件。

## 事务与外部调用规则

- `@Transactional` 只放在 Service 层，事务范围保持最小。
- 数据库事务内不得调用 LLM、S3/RustFS 或外部 HTTP。
- Redis Stream 消费者先校验实体，再决定处理或 ACK 丢弃。
- 外部调用与数据库写入需要分阶段编排；外部调用失败时使用现有状态和错误码恢复，不通过吞异常维持成功状态。

## 重构与回滚规则

每个职责拆分作为一个独立提交，提交前使用指定的 Java 25 和本机 PowerShell 执行：

1. `git diff --check`；
2. 启动 `:app:bootRun --no-daemon` 并检查 `/actuator/health`；
3. 停止本步骤启动的后端；
4. 执行 `:app:test --no-daemon`；
5. 测试成功后使用中文提交信息。

单步回滚使用 `git revert <步骤提交哈希>`，不使用覆盖工作树的 reset 或 checkout。
