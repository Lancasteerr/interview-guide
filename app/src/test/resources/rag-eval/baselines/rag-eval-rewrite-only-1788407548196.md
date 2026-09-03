# RAG 测评报告：rag-eval-rewrite-only-1788407548196

## 运行环境

- gitSha: 5767d373a2865a0e272e30fd59b127fa23e33c8e
- datasetSha256: ad3f88c2cb3f5015
- fixtureSha256:java-guide.md: 081a5d118866a258
- fixtureSha256:project-guide.md: e139cc1db203f1e6
- promptSha256:knowledgebase-query-system.st: fe8d276e4d7ad6e1
- promptSha256:knowledgebase-query-user.st: 6e309171d460a64c
- promptSha256:knowledgebase-query-rewrite.st: f4e622ad2784bd87
- rewriteEnabled: true
- mergeOriginalQuery: false(默认)
- redisDatabase: 1(rag-eval Profile 默认)
- evalDatabase: interview_guide_rag_eval（每 run 重建）
- tokenUsage: null（当前链路 .content() 无法取得 usage，补齐属 P1-04）
- springAiVersion: 2.0.0
- provider:dashscope.model: qwen3.5-flash
- provider:dashscope.embeddingModel: text-embedding-v3
- provider:dashscope.temperature: null
- defaultChatProvider: dashscope
- defaultEmbeddingProvider: dashscope
- chunkCount:java-guide.md: 25
- chunkCount:project-guide.md: 21

## 汇总指标

### overall

- samples: 40
- harnessErrorCount: 0
- inScope: 32
- Hit@K(%): 93.75
- MRR: 0.7081
- EvidenceRecall@K: 0.9219
- retrievalMsP50: 154
- retrievalMsP95: 199
- rewriteMsP50: 36588
- rewriteMsP95: 51077
- generationMsP50: 21558
- generationMsP95: 41554
- endToEndMsP50: 65959
- endToEndMsP95: 88146

### dev

- samples: 30
- harnessErrorCount: 0
- inScope: 23
- Hit@K(%): 95.6522
- MRR: 0.7134
- EvidenceRecall@K: 0.9565
- retrievalMsP50: 153
- retrievalMsP95: 198
- rewriteMsP50: 39065
- rewriteMsP95: 76540
- generationMsP50: 20695
- generationMsP95: 41554
- endToEndMsP50: 59422
- endToEndMsP95: 88146

### holdout

- samples: 10
- harnessErrorCount: 0
- inScope: 9
- Hit@K(%): 88.8889
- MRR: 0.6944
- EvidenceRecall@K: 0.8333
- retrievalMsP50: 164
- retrievalMsP95: 208
- rewriteMsP50: 36588
- rewriteMsP95: 47303
- generationMsP50: 24319
- generationMsP95: 24319
- endToEndMsP50: 71781
- endToEndMsP95: 71781

### tag:同义改写题

- samples: 8
- harnessErrorCount: 0
- inScope: 8
- Hit@K(%): 87.5
- MRR: 0.5
- EvidenceRecall@K: 0.875
- retrievalMsP50: 161
- retrievalMsP95: 193
- rewriteMsP50: 39065
- rewriteMsP95: 51077
- generationMsP50: 8950
- generationMsP95: 8950
- endToEndMsP50: 36969
- endToEndMsP95: 36969

### tag:多轮上下文题

- samples: 6
- harnessErrorCount: 0
- inScope: 6
- Hit@K(%): 100.0
- MRR: 0.9167
- EvidenceRecall@K: 1.0
- retrievalMsP50: 137
- retrievalMsP95: 157
- rewriteMsP50: 32631
- rewriteMsP95: 48072
- generationMsP50: 7715
- generationMsP95: 7715
- endToEndMsP50: 24512
- endToEndMsP95: 24512

### tag:易误召回

- samples: 1
- harnessErrorCount: 0
- retrievalMsP50: 141
- retrievalMsP95: 141
- rewriteMsP50: 47303
- rewriteMsP95: 47303
- generationMsP50: 24319
- generationMsP95: 24319
- endToEndMsP50: 71781
- endToEndMsP95: 71781

### tag:直接事实题

- samples: 12
- harnessErrorCount: 0
- inScope: 12
- Hit@K(%): 91.6667
- MRR: 0.6521
- EvidenceRecall@K: 0.9167
- retrievalMsP50: 164
- retrievalMsP95: 198
- rewriteMsP50: 34289
- rewriteMsP95: 47181
- generationMsP50: 19025
- generationMsP95: 26392
- endToEndMsP50: 54643
- endToEndMsP95: 77104

### tag:知识库外题

- samples: 8
- harnessErrorCount: 0
- retrievalMsP50: 141
- retrievalMsP95: 304
- rewriteMsP50: 46393
- rewriteMsP95: 341786
- generationMsP50: 24319
- generationMsP95: 42229
- endToEndMsP50: 82831
- endToEndMsP95: 362797

### tag:跨段题

- samples: 6
- harnessErrorCount: 0
- inScope: 6
- Hit@K(%): 100.0
- MRR: 0.8889
- EvidenceRecall@K: 0.9167
- retrievalMsP50: 151
- retrievalMsP95: 199
- rewriteMsP50: 39148
- rewriteMsP95: 46187
- generationMsP50: 35386
- generationMsP95: 35386
- endToEndMsP50: 75597
- endToEndMsP95: 75597

## 拒答混淆矩阵

- tp: 0
- fn: 8
- fp: 0
- tn: 10
- accuracy: 0.5556
- precision: 0.0
- recall: 0.0
- f1: 0.0

## 坏例

### fact-07 [LOW_FIRST_RANK]

- question: Redis 的 RDB 和 AOF 两种持久化方式各自的原理是什么？
- tags: [直接事实题]
- detail: 首个命中排名=5

### fact-08 [LOW_FIRST_RANK]

- question: AOF 的 appendfsync 有哪几种刷盘策略？默认是哪种？
- tags: [直接事实题]
- detail: 首个命中排名=8

### fact-09 [NO_EVIDENCE_HIT]

- question: 什么是回表？为什么会发生回表？
- tags: [直接事实题]
- detail: Top-K 内未命中任何证据锚点

### para-03 [LOW_FIRST_RANK]

- question: Redis 机器要是断电了，里面的数据会不会全没了？
- tags: [同义改写题]
- detail: 首个命中排名=4

### para-06 [NO_EVIDENCE_HIT]

- question: 像双十一那种量级的单子，你们后端的库是怎么撑住的？
- tags: [同义改写题]
- detail: Top-K 内未命中任何证据锚点

### para-08 [LOW_FIRST_RANK]

- question: Redis 分布式锁眼瞅着要过期了业务还没跑完，有什么续命的办法？
- tags: [同义改写题]
- detail: 首个命中排名=4

### chunk-06 [EVIDENCE_INCOMPLETE]

- question: 数据库连接池那次线上事故的根因是什么？后来做了哪些改进？
- tags: [跨段题]
- detail: 证据锚点未全部召回，Recall=0.5

### oos-01 [OOS_NOT_REJECTED]

- question: Kafka 消费者组的 rebalance 流程是怎么触发的？
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-02 [OOS_NOT_REJECTED]

- question: RabbitMQ 的镜像队列应该怎么配置？
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-03 [OOS_NOT_REJECTED]

- question: 公司 2024 年双十一的整体 GMV 是多少？
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-04 [OOS_NOT_REJECTED]

- question: Netty 的 EventLoop 线程模型是怎么工作的？
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-05 [OOS_NOT_REJECTED]

- question: 项目组里张三的手机号和邮箱是多少？
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-06 [OOS_NOT_REJECTED]

- question: Elasticsearch 倒排索引的写入流程讲一下
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-07 [OOS_NOT_REJECTED]

- question: Go 语言 GMP 调度模型的抢占是怎么实现的？
- tags: [知识库外题]
- detail: 知识库外问题未被拒答

### oos-08 [OOS_NOT_REJECTED]

- question: CMS 回收器是在 JDK 哪个版本被正式移除的？
- tags: [知识库外题, 易误召回]
- detail: 知识库外问题未被拒答

## 忠实度人工复核对照表（PENDING 待复核）

### fact-01

- question: G1 回收器的堆内存划分方式和默认停顿目标是什么？
- expectedEvidence: [G1 回收器把堆划分为多个等大的 Region，默认期望停顿目标是 200 毫秒]
- answer: ## G1 回收器的堆内存划分方式与默认停顿目标

基于检索到的知识库内容，G1 回收器的堆内存划分及停顿目标信息如下：

- **堆内存划分方式**：G1 回收器把堆划分为多个等大的 Region。
- **默认停顿目标**：默认期望停顿目标是 200 毫秒。

在 JVM 生产实践中，若选择 G1 回收器，Region 大小建议按大对象体积进行评估。
- faithfulnessStatus: **PENDING**

### fact-02

- question: ZGC 回收器的停顿时间和支持的堆规模是多少？
- expectedEvidence: [停顿时间不超过 1 毫秒，支持 TB 级堆]
- answer: ## ZGC 回收器性能指标

根据检索到的知识库内容，ZGC 回收器的核心特性如下：

- **停顿时间**：不超过 1 毫秒。
- **支持的堆规模**：支持 TB 级堆。

以上信息出自文档中的“JVM 垃圾回收”章节，其中明确指出 ZGC 是面向低延迟的回收器。
- faithfulnessStatus: **PENDING**

### fact-03

- question: HashMap 链表什么时候会转换成红黑树？
- expectedEvidence: [当链表长度超过 8 且数组容量达到 64 时，链表转换为红黑树]
- answer: ## HashMap 链表转红黑树的转换条件

根据知识库内容，HashMap 链表转换为红黑树必须同时满足以下两个阈值条件：

- **链表长度条件**：链表长度必须超过 8。
- **数组容量条件**：数组容量必须达到 64。

只有在上述两个条件同时满足的情况下，链表才会转换为红黑树。如果只满足链表长度条件而数组容量不足 64，系统会优先执行扩容操作而不是树化。

此外，知识库中还提到了树化后的退化条件：当红黑树节点数收缩到 6 时，会退化回链表。设置这两个阈值的差异是为了抑制在边界附近反复转换产生的抖动。
- faithfulnessStatus: **PENDING**

### fact-04

- question: HashMap 的默认初始容量和负载因子是多少？扩容规则是什么？
- expectedEvidence: [默认初始容量是 16，负载因子是 0.75]
- answer: ## HashMap 容量与扩容机制

根据知识库中 `## HashMap 与 ConcurrentHashMap` 章节的内容，HashMap 的默认初始容量、负载因子及扩容规则如下：

- **默认初始容量**：16
- **负载因子**：0.75
- **扩容触发条件**：元素数量超过容量乘以负载因子时触发扩容
- **扩容规则**：扩容为原来的两倍

此外，HashMap 的容量总是 2 的整数次幂，这样 hash 值对容量取模可以转化为位运算 `(n - 1) & hash`。
- faithfulnessStatus: **PENDING**

### fact-05

- question: 线程池的构造函数有哪七个核心参数？
- expectedEvidence: [corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue、threadFactory、handler]
- answer: ## 线程池构造函数的七个核心参数

根据知识库内容，线程池的核心参数一共有七个，它们共同决定了任务提交后的处理流程。这七个核心参数分别是：

1. **corePoolSize**
2. **maximumPoolSize**
3. **keepAliveTime**
4. **unit**
5. **workQueue**
6. **threadFactory**
7. **handler**

这些参数的作用体现在任务提交的逻辑中：当线程数小于 corePoolSize 时直接创建核心线程；核心线程满后任务进入工作队列；队列满后创建非核心线程直至 maximumPoolSize；再满则触发拒绝策略。
- faithfulnessStatus: **PENDING**

### para-05

- question: 消息队列那边消费一直失败，你们最多让它重试几次？超时了咋处理？
- expectedEvidence: [消费失败最多重试 3 次, 进入死信流 dead-letter-stream 人工介入]
- answer: ## 消息消费失败重试与超时处理

根据知识库中的消息处理规范，针对消费失败的处理机制如下：

### 重试次数限制

消费失败最多重试 **3 次**，这是为了避免无限重试拖垮下游系统。具体重试节奏为：

- 第 1 次重试：间隔 1 秒
- 第 2 次重试：间隔 5 秒
- 第 3 次重试：间隔 30 秒

采用阶梯递增的方式，给系统一定的恢复时间。

### 超时后的处理方式

超过 3 次重试仍然失败的消息，不会继续原地重试，而是进入以下处理流程：

1. **进入死信流**：消息被路由到 `dead-letter-stream` 死信通道
2. **人工介入**：死信流的消息会进入管理后台，由值班同学或相关人员进行处理
3. **死信处理平台功能**：
   - 支持按错误类型分组
   - 支持批量重放
   - 支持单条诊断与错误备注
4. **定期复盘**：每周复盘死信 top 错误类型
   - 属于代码缺陷的进迭代修复
   - 属于数据问题的写修复脚本

### 配套保障措施

为确保消息处理的可靠性，团队还实施了以下措施：

- **幂等性保障**：消费者处理前先查幂等表，处理成功后写入处理记录
- **消息体设计**：只携带稳定 ID，消费者以数据库实体为事实来源
- **补偿任务**：每小时扫描一次超时未完成的订单状态机节点，对卡住的节点按状态机定义重放

这套机制确保了消息消费失败不会无限循环，同时提供了完善的后续处理路径。
- faithfulnessStatus: **PENDING**

### ctx-01

- question: 它的默认停顿目标是多少？
- expectedEvidence: [默认期望停顿目标是 200 毫秒]
- answer: ## G1 回收器默认停顿目标

根据知识库内容，G1 回收器的默认期望停顿目标是 **200 毫秒**。

---

### 相关背景信息

知识库中提到：

> JVM 垃圾回收器的发展经历了 Serial、Parallel、CMS、G1、ZGC 几代。G1 回收器把堆划分为多个等大的 Region，默认期望停顿目标是 200 毫秒。

这一设计使得 G1 适合大堆低停顿场景，与 ZGC 相比，ZGC 是面向低延迟的回收器，停顿时间不超过 1 毫秒，支持 TB 级堆。
- faithfulnessStatus: **PENDING**

### ctx-02

- question: 消费失败最多让它重试几次？
- expectedEvidence: [消费失败最多重试 3 次]
- answer: ## 消费失败重试次数

根据知识库中的消息失败处理细则，消费失败的重试次数有明确规定：

### 重试次数限制

- **最多重试 3 次**：消费失败的基础规则很克制，消费失败最多重试 3 次，避免无限重试拖垮下游。

### 重试策略细节

1. **重试间隔**：重试间隔按 1 秒、5 秒、30 秒阶梯递增

2. **失败后处理**：
   - 超过 3 次的消息不再原地重试
   - 进入死信流 `dead-letter-stream` 人工介入
   - 最终进入人工处理流程

### 相关保障机制

- **幂等性保障**：依靠订单号加业务类型的唯一索引，消费者处理前先查幂等表，处理成功后写入处理记录
- **消息堆积告警**：阈值设置为 1 万条，触发告警后值班同学先扩容消费者再排查慢消费根因
- **死信处理平台**：支持按错误类型分组、批量重放、单条诊断与错误备注

> 知识库原文："消费失败的基础规则很克制：消费失败最多重试 3 次，避免无限重试拖垮下游。超过次数的消息不再原地重试，进入人工处理流程。"
- faithfulnessStatus: **PENDING**

### chunk-01

- question: 订单中台用了哪几条 Redis Stream？消费失败后的完整处理规则是什么？
- expectedEvidence: [order-stream、inventory-stream、delivery-stream, 消费失败最多重试 3 次, 重试间隔按 1 秒、5 秒、30 秒阶梯递增]
- answer: ## Redis Stream 使用情况

根据知识库内容，订单履约链路使用 Redis Stream 作为消息通道，Stream 按业务领域拆分为以下三条：

- order-stream
- inventory-stream
- delivery-stream

这三条 Stream 的消费者组名称按服务实例编号区分，消费位点由消费者组自动维护。

## 消费失败处理规则

消费失败的处理遵循严格的重试与兜底机制，具体规则如下：

1. **重试策略**
   - 消费失败最多重试 3 次，避免无限重试拖垮下游。
   - 重试间隔按 1 秒、5 秒、30 秒阶梯递增。

2. **死信处理**
   - 3 次仍失败的消息不再原地重试，进入死信流（dead-letter-stream）进行人工介入。
   - 死信流的消息进入管理后台，支持按错误类型分组、批量重放、单条诊断与错误备注。
   - 每周复盘死信 top 错误类型，属于代码缺陷的进迭代修复，属于数据问题的写修复脚本。

3. **监控与告警**
   - 消息堆积告警阈值设置为 1 万条。
   - 触发告警后，值班同学先扩容消费者再排查慢消费根因。

4. **幂等与补偿**
   - 幂等性保障依靠订单号加业务类型的唯一索引，消费者处理前先查幂等表，处理成功后写入处理记录。
   - 补偿任务每小时扫描一次超时未完成的订单状态机节点，对卡住的节点按状态机定义重放。
- faithfulnessStatus: **PENDING**

### chunk-02

- question: 线程池拒绝时 JDK 有哪些内置策略？生产环境更推荐怎么落地？
- expectedEvidence: [AbortPolicy 抛出异常、CallerRunsPolicy 由提交线程执行、DiscardPolicy 静默丢弃、DiscardOldestPolicy 丢弃队首最老任务, 生产环境建议使用自定义拒绝策略配合监控告警]
- answer: ## 线程池拒绝策略与生产实践

### JDK 内置拒绝策略
根据知识库内容，JDK 线程池内置了四种拒绝策略，具体如下：

- **AbortPolicy**：抛出异常
- **CallerRunsPolicy**：由提交线程执行
- **DiscardPolicy**：静默丢弃
- **DiscardOldestPolicy**：丢弃队首最老任务

### 生产环境落地建议
在生产环境中，知识库明确建议不要直接使用默认的 AbortPolicy，而是采用以下落地方案：

1. **策略选择**：生产环境建议使用自定义拒绝策略配合监控告警。
2. **自定义策略实现**：在自定义策略里至少要做三件事：
   - 记录被拒绝任务上下文
   - 递增监控计数器
   - 按业务决定是降级执行还是快速失败返回
3. **上线前检查项**：
   - 队列必须有界防止 OOM
   - 拒绝策略必须可观测
   - 核心参数必须可配置而不是硬编码
- faithfulnessStatus: **PENDING**

## 逐样本明细

| id | split | tags | outcome | hit | firstRank | evidenceRecall | totalMs |
|---|---|---|---|---|---|---|---|
| fact-01 | dev | [直接事实题] | ANSWERED | true | 1 | 1.0 | 37335 |
| fact-02 | dev | [直接事实题] | ANSWERED | true | 1 | 1.0 | 65959 |
| fact-03 | dev | [直接事实题] | ANSWERED | true | 1 | 1.0 | 54643 |
| fact-04 | dev | [直接事实题] | ANSWERED | true | 2 | 1.0 | 40539 |
| fact-05 | dev | [直接事实题] | ANSWERED | true | 2 | 1.0 | 77104 |
| fact-06 | dev | [直接事实题] | RETRIEVED | true | 1 | 1.0 | 47344 |
| fact-07 | dev | [直接事实题] | RETRIEVED | true | 5 | 1.0 | 20212 |
| fact-08 | dev | [直接事实题] | RETRIEVED | true | 8 | 1.0 | 29306 |
| fact-09 | holdout | [直接事实题] | RETRIEVED | false | null | 0.0 | 30075 |
| fact-10 | holdout | [直接事实题] | RETRIEVED | true | 2 | 1.0 | 34406 |
| fact-11 | holdout | [直接事实题] | RETRIEVED | true | 1 | 1.0 | 38145 |
| fact-12 | holdout | [直接事实题] | RETRIEVED | true | 1 | 1.0 | 42561 |
| para-01 | dev | [同义改写题] | RETRIEVED | true | 1 | 1.0 | 51211 |
| para-02 | dev | [同义改写题] | RETRIEVED | true | 1 | 1.0 | 28344 |
| para-03 | dev | [同义改写题] | RETRIEVED | true | 4 | 1.0 | 42347 |
| para-04 | dev | [同义改写题] | RETRIEVED | true | 2 | 1.0 | 39205 |
| para-05 | dev | [同义改写题] | ANSWERED | true | 2 | 1.0 | 36969 |
| para-06 | dev | [同义改写题] | RETRIEVED | false | null | 0.0 | 39743 |
| para-07 | holdout | [同义改写题] | RETRIEVED | true | 2 | 1.0 | 37340 |
| para-08 | holdout | [同义改写题] | RETRIEVED | true | 4 | 1.0 | 24888 |
| ctx-01 | dev | [多轮上下文题] | ANSWERED | true | 1 | 1.0 | 24512 |
| ctx-02 | dev | [多轮上下文题] | ANSWERED | true | 2 | 1.0 | 22914 |
| ctx-03 | dev | [多轮上下文题] | RETRIEVED | true | 1 | 1.0 | 35015 |
| ctx-04 | dev | [多轮上下文题] | RETRIEVED | true | 1 | 1.0 | 48227 |
| ctx-05 | dev | [多轮上下文题] | RETRIEVED | true | 1 | 1.0 | 32758 |
| ctx-06 | holdout | [多轮上下文题] | RETRIEVED | true | 1 | 1.0 | 30717 |
| chunk-01 | dev | [跨段题] | ANSWERED | true | 1 | 1.0 | 68065 |
| chunk-02 | dev | [跨段题] | ANSWERED | true | 1 | 1.0 | 75597 |
| chunk-03 | dev | [跨段题] | RETRIEVED | true | 3 | 1.0 | 39274 |
| chunk-04 | dev | [跨段题] | RETRIEVED | true | 1 | 1.0 | 46342 |
| chunk-05 | holdout | [跨段题] | RETRIEVED | true | 1 | 1.0 | 31963 |
| chunk-06 | holdout | [跨段题] | RETRIEVED | true | 1 | 0.5 | 36787 |
| oos-01 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 362797 |
| oos-02 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 88146 |
| oos-03 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 82831 |
| oos-04 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 59422 |
| oos-05 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 85198 |
| oos-06 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 49970 |
| oos-07 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 39860 |
| oos-08 | holdout | [知识库外题, 易误召回] | ANSWERED | false | null | 1.0 | 71781 |
