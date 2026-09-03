# RAG 测评报告：rag-eval-baseline-1788403462034

## 运行环境

- gitSha: e247d8e8fc371e49655223620fd006bbbd64fa13
- datasetSha256: ad3f88c2cb3f5015
- fixtureSha256:java-guide.md: 081a5d118866a258
- fixtureSha256:project-guide.md: e139cc1db203f1e6
- promptSha256:knowledgebase-query-system.st: fe8d276e4d7ad6e1
- promptSha256:knowledgebase-query-user.st: 6e309171d460a64c
- promptSha256:knowledgebase-query-rewrite.st: f4e622ad2784bd87
- rewriteEnabled: false(rag-eval Profile 默认)
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
- Hit@K(%): 96.875
- MRR: 0.6818
- EvidenceRecall@K: 0.9531
- retrievalMsP50: 161
- retrievalMsP95: 207
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 18730
- generationMsP95: 29928
- endToEndMsP50: 18873
- endToEndMsP95: 30137

### dev

- samples: 30
- harnessErrorCount: 0
- inScope: 23
- Hit@K(%): 100.0
- MRR: 0.6572
- EvidenceRecall@K: 1.0
- retrievalMsP50: 169
- retrievalMsP95: 228
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 18730
- generationMsP95: 29928
- endToEndMsP50: 18873
- endToEndMsP95: 30137

### holdout

- samples: 10
- harnessErrorCount: 0
- inScope: 9
- Hit@K(%): 88.8889
- MRR: 0.7444
- EvidenceRecall@K: 0.8333
- retrievalMsP50: 161
- retrievalMsP95: 181
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 15368
- generationMsP95: 15368
- endToEndMsP50: 15505
- endToEndMsP95: 15505

### tag:同义改写题

- samples: 8
- harnessErrorCount: 0
- inScope: 8
- Hit@K(%): 100.0
- MRR: 0.6083
- EvidenceRecall@K: 1.0
- retrievalMsP50: 169
- retrievalMsP95: 207
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 22678
- generationMsP95: 22678
- endToEndMsP50: 22857
- endToEndMsP95: 22857

### tag:多轮上下文题

- samples: 6
- harnessErrorCount: 0
- inScope: 6
- Hit@K(%): 100.0
- MRR: 0.6319
- EvidenceRecall@K: 1.0
- retrievalMsP50: 193
- retrievalMsP95: 206
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 17705
- generationMsP95: 17705
- endToEndMsP50: 17911
- endToEndMsP95: 17911

### tag:易误召回

- samples: 1
- harnessErrorCount: 0
- retrievalMsP50: 125
- retrievalMsP95: 125
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 15368
- generationMsP95: 15368
- endToEndMsP50: 15505
- endToEndMsP95: 15505

### tag:直接事实题

- samples: 12
- harnessErrorCount: 0
- inScope: 12
- Hit@K(%): 91.6667
- MRR: 0.6521
- EvidenceRecall@K: 0.9167
- retrievalMsP50: 161
- retrievalMsP95: 182
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 18730
- generationMsP95: 19757
- endToEndMsP50: 18873
- endToEndMsP95: 19976

### tag:知识库外题

- samples: 8
- harnessErrorCount: 0
- retrievalMsP50: 196
- retrievalMsP95: 331
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 22985
- generationMsP95: 29928
- endToEndMsP50: 23152
- endToEndMsP95: 30137

### tag:跨段题

- samples: 6
- harnessErrorCount: 0
- inScope: 6
- Hit@K(%): 100.0
- MRR: 0.8889
- EvidenceRecall@K: 0.9167
- retrievalMsP50: 140
- retrievalMsP95: 177
- rewriteMsP50: 0
- rewriteMsP95: 0
- generationMsP50: 31520
- generationMsP95: 31520
- endToEndMsP50: 31670
- endToEndMsP95: 31670

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
- detail: 首个命中排名=6

### para-08 [LOW_FIRST_RANK]

- question: Redis 分布式锁眼瞅着要过期了业务还没跑完，有什么续命的办法？
- tags: [同义改写题]
- detail: 首个命中排名=5

### ctx-04 [LOW_FIRST_RANK]

- question: 默认自带那个直接抛异常的策略叫什么名字？
- tags: [多轮上下文题]
- detail: 首个命中排名=8

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

基于检索到的知识库内容，关于 G1 回收器的堆内存划分和停顿目标信息如下：

- **堆内存划分方式**：G1 回收器把堆划分为多个**等大的 Region**。
- **默认停顿目标**：默认期望停顿目标是**200 毫秒**。

另外，知识库在 JVM 生产实践部分补充说明，选择 G1 时 Region 大小可按大对象体积进行评估。
- faithfulnessStatus: **PASS**

### fact-02

- question: ZGC 回收器的停顿时间和支持的堆规模是多少？
- expectedEvidence: [停顿时间不超过 1 毫秒，支持 TB 级堆]
- answer: ## ZGC 回收器的停顿时间与堆规模

根据知识库中“JVM 垃圾回收”章节的内容，ZGC 回收器的相关指标如下：

- **停顿时间**：不超过 1 毫秒
- **支持的堆规模**：支持 TB 级堆

该信息明确记载于文档关于 JVM 垃圾回收器发展的描述部分，原文指出："ZGC 是面向低延迟的回收器，停顿时间不超过 1 毫秒，支持 TB 级堆。”
- faithfulnessStatus: **PASS**

### fact-03

- question: HashMap 链表什么时候会转换成红黑树？
- expectedEvidence: [当链表长度超过 8 且数组容量达到 64 时，链表转换为红黑树]
- answer: ## HashMap 链表转红黑树的转换条件

根据知识库内容，HashMap 链表转换为红黑树必须同时满足两个阈值条件。

- 链表长度超过 8
- 数组容量达到 64

如果只满足链表长度条件而数组容量不足 64 时，系统会优先选择扩容而不是树化。

树化后退化的条件是红黑树节点数收缩到 6 时，退化回链表。这两个阈值差的设计用于抑制在边界附近反复转换的抖动。
- faithfulnessStatus: **PASS**

### fact-04

- question: HashMap 的默认初始容量和负载因子是多少？扩容规则是什么？
- expectedEvidence: [默认初始容量是 16，负载因子是 0.75]
- answer: ## HashMap 默认参数与扩容规则

根据知识库中《Java 后端核心知识手册》关于 HashMap 的描述，其默认参数及扩容机制如下：

1. **默认初始容量**：默认为 16。
2. **负载因子**：默认为 0.75。

3. **扩容规则**：
   - **触发条件**：当元素数量超过容量乘以负载因子时触发扩容。
   - **扩容方式**：扩容为原来的两倍。
   - **容量特性**：HashMap 的容量总是 2 的整数次幂，以便将 hash 值对容量取模转化为位运算 `(n - 1) & hash`。
- faithfulnessStatus: **PASS**

### fact-05

- question: 线程池的构造函数有哪七个核心参数？
- expectedEvidence: [corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue、threadFactory、handler]
- answer: ## 线程池核心参数

根据知识库内容，线程池的核心参数一共有七个，具体包括以下项：

1. **corePoolSize**
2. **maximumPoolSize**
3. **keepAliveTime**
4. **unit**
5. **workQueue**
6. **threadFactory**
7. **handler**

这些参数共同决定了线程池的任务处理逻辑。任务提交后，若线程数小于 corePoolSize 会直接创建核心线程；核心线程满后任务进入工作队列；队列满后创建非核心线程直至 maximumPoolSize；若仍满则触发拒绝策略。
- faithfulnessStatus: **PASS**

### para-05

- question: 消息队列那边消费一直失败，你们最多让它重试几次？超时了咋处理？
- expectedEvidence: [消费失败最多重试 3 次, 进入死信流 dead-letter-stream 人工介入]
- answer: ## 消息消费失败重试策略

根据知识库中的“消息失败处理细则”与“重试的节奏与兜底”章节，消息队列消费失败的处理规则如下：

- **最大重试次数**：消费失败最多重试 **3 次**，以避免无限重试拖垮下游系统。
- **重试间隔节奏**：重试间隔按 **1 秒、5 秒、30 秒** 阶梯递增。

## 超时与失败后的处理流程

当消息超过重试次数或出现超时情况时，采取以下处理措施：

- **进入死信流**：3 次仍失败的消息不再原地重试，而是进入 **死信流（dead-letter-stream）** 进行人工介入。
- **死信管理平台**：死信流的消息进入管理后台，支持按错误类型分组、批量重放、单条诊断与错误备注。
- **后续优化机制**：每周复盘死信 top 错误类型，属于代码缺陷的进迭代修复，属于数据问题的写修复脚本。
- **状态机补偿**：针对超时未完成的订单状态机节点，补偿任务每小时扫描一次，对卡住的节点按状态机定义重放。
- faithfulnessStatus: **PASS**

### ctx-01

- question: 它的默认停顿目标是多少？
- expectedEvidence: [默认期望停顿目标是 200 毫秒]
- answer: ## G1 回收器默认停顿目标

根据知识库中 **JVM 垃圾回收** 章节的内容，G1 回收器的默认期望停顿目标是 **200 毫秒**。

具体信息如下：
- G1 回收器把堆划分为多个等大的 Region。
- 默认期望停顿目标是 200 毫秒。
- 它是面向服务端的回收器，适合大堆低停顿场景。
- faithfulnessStatus: **PASS**

### ctx-02

- question: 消费失败最多让它重试几次？
- expectedEvidence: [消费失败最多重试 3 次]
- answer: ## 消费失败重试次数说明

根据知识库中的“消息失败处理细则”及相关文档描述，消费失败的重试策略如下：

- **最大重试次数**：消费失败最多重试 **3 次**，避免无限重试拖垮下游。
- **重试间隔节奏**：重试间隔按 **1 秒、5 秒、30 秒** 阶梯递增。
- **兜底处理机制**：3 次仍失败的消息进入 **死信流（dead-letter-stream）**，转为人工介入处理，不再原地重试。

该规则主要应用于订单履约链路等使用 Redis Stream 的场景，以确保消息处理的可靠性与系统稳定性。
- faithfulnessStatus: **PASS**

### chunk-01

- question: 订单中台用了哪几条 Redis Stream？消费失败后的完整处理规则是什么？
- expectedEvidence: [order-stream、inventory-stream、delivery-stream, 消费失败最多重试 3 次, 重试间隔按 1 秒、5 秒、30 秒阶梯递增]
- answer: ## Redis Stream 使用情况

根据知识库内容，订单履约链路使用 Redis Stream 作为消息通道，Stream 按业务领域拆分，具体包含以下三条：

1. `order-stream`
2. `inventory-stream`
3. `delivery-stream`

## 消费失败处理规则

消费失败后的完整处理流程遵循以下规则：

1. **重试机制**：消费失败最多重试 3 次，避免无限重试拖垮下游。重试间隔按 1 秒、5 秒、30 秒阶梯递增。
2. **死信流转**：超过 3 次重试仍失败的消息不再原地重试，进入死信流 `dead-letter-stream` 进行人工介入。
3. **人工处理**：死信流的消息进入管理后台，支持按错误类型分组、批量重放、单条诊断与错误备注。属于代码缺陷的进迭代修复，属于数据问题的写修复脚本。
4. **异常监控**：消息堆积告警阈值设置为 1 万条，触发告警后值班同学先扩容消费者再排查慢消费根因。
5. **幂等保障**：消费者处理前先查幂等表（依靠订单号加业务类型的唯一索引），处理成功后写入处理记录，防止重复消费。
- faithfulnessStatus: **PASS**

### chunk-02

- question: 线程池拒绝时 JDK 有哪些内置策略？生产环境更推荐怎么落地？
- expectedEvidence: [AbortPolicy 抛出异常、CallerRunsPolicy 由提交线程执行、DiscardPolicy 静默丢弃、DiscardOldestPolicy 丢弃队首最老任务, 生产环境建议使用自定义拒绝策略配合监控告警]
- answer: ## JDK 内置拒绝策略

根据知识库内容，线程池任务提交后若触发拒绝策略，JDK 内置的四种策略如下：

- **AbortPolicy**：抛出异常
- **CallerRunsPolicy**：由提交线程执行
- **DiscardPolicy**：静默丢弃
- **DiscardOldestPolicy**：丢弃队首最老任务

## 生产环境落地推荐

在生产环境中，关于拒绝策略的落地与配置有以下具体建议：

- **策略选择**：不建议直接使用默认的 AbortPolicy，生产环境建议使用自定义拒绝策略配合监控告警。
- **自定义实现要求**：在自定义策略里至少要做三件事，包括记录被拒绝任务上下文、递增监控计数器、按业务决定是降级执行还是快速失败返回。
- **配置检查项**：线程池上线前的检查项中强调队列必须有界防止 OOM，拒绝策略必须可观测，核心参数必须可配置而不是硬编码。
- **运行观测**：运行时需关注拒绝次数等指标，队列长度持续超过容量八成就要评估扩容或异步化，避免等到拒绝发生再处理。
- faithfulnessStatus: **PASS**

## 逐样本明细

| id | split | tags | outcome | hit | firstRank | evidenceRecall | totalMs |
|---|---|---|---|---|---|---|---|
| fact-01 | dev | [直接事实题] | ANSWERED | true | 1 | 1.0 | 19976 |
| fact-02 | dev | [直接事实题] | ANSWERED | true | 1 | 1.0 | 18492 |
| fact-03 | dev | [直接事实题] | ANSWERED | true | 1 | 1.0 | 19258 |
| fact-04 | dev | [直接事实题] | ANSWERED | true | 2 | 1.0 | 18873 |
| fact-05 | dev | [直接事实题] | ANSWERED | true | 2 | 1.0 | 16281 |
| fact-06 | dev | [直接事实题] | RETRIEVED | true | 1 | 1.0 | 131 |
| fact-07 | dev | [直接事实题] | RETRIEVED | true | 5 | 1.0 | 183 |
| fact-08 | dev | [直接事实题] | RETRIEVED | true | 8 | 1.0 | 229 |
| fact-09 | holdout | [直接事实题] | RETRIEVED | false | null | 0.0 | 152 |
| fact-10 | holdout | [直接事实题] | RETRIEVED | true | 2 | 1.0 | 169 |
| fact-11 | holdout | [直接事实题] | RETRIEVED | true | 1 | 1.0 | 161 |
| fact-12 | holdout | [直接事实题] | RETRIEVED | true | 1 | 1.0 | 182 |
| para-01 | dev | [同义改写题] | RETRIEVED | true | 2 | 1.0 | 116 |
| para-02 | dev | [同义改写题] | RETRIEVED | true | 1 | 1.0 | 131 |
| para-03 | dev | [同义改写题] | RETRIEVED | true | 6 | 1.0 | 183 |
| para-04 | dev | [同义改写题] | RETRIEVED | true | 2 | 1.0 | 179 |
| para-05 | dev | [同义改写题] | ANSWERED | true | 1 | 1.0 | 22857 |
| para-06 | dev | [同义改写题] | RETRIEVED | true | 2 | 1.0 | 207 |
| para-07 | holdout | [同义改写题] | RETRIEVED | true | 1 | 1.0 | 114 |
| para-08 | holdout | [同义改写题] | RETRIEVED | true | 5 | 1.0 | 163 |
| ctx-01 | dev | [多轮上下文题] | ANSWERED | true | 1 | 1.0 | 14281 |
| ctx-02 | dev | [多轮上下文题] | ANSWERED | true | 3 | 1.0 | 17911 |
| ctx-03 | dev | [多轮上下文题] | RETRIEVED | true | 1 | 1.0 | 207 |
| ctx-04 | dev | [多轮上下文题] | RETRIEVED | true | 8 | 1.0 | 128 |
| ctx-05 | dev | [多轮上下文题] | RETRIEVED | true | 3 | 1.0 | 198 |
| ctx-06 | holdout | [多轮上下文题] | RETRIEVED | true | 1 | 1.0 | 177 |
| chunk-01 | dev | [跨段题] | ANSWERED | true | 1 | 1.0 | 31670 |
| chunk-02 | dev | [跨段题] | ANSWERED | true | 1 | 1.0 | 18775 |
| chunk-03 | dev | [跨段题] | RETRIEVED | true | 3 | 1.0 | 178 |
| chunk-04 | dev | [跨段题] | RETRIEVED | true | 1 | 1.0 | 156 |
| chunk-05 | holdout | [跨段题] | RETRIEVED | true | 1 | 1.0 | 140 |
| chunk-06 | holdout | [跨段题] | RETRIEVED | true | 1 | 0.5 | 126 |
| oos-01 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 25864 |
| oos-02 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 13799 |
| oos-03 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 30137 |
| oos-04 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 17601 |
| oos-05 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 16889 |
| oos-06 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 23152 |
| oos-07 | dev | [知识库外题] | ANSWERED | false | null | 1.0 | 25202 |
| oos-08 | holdout | [知识库外题, 易误召回] | ANSWERED | false | null | 1.0 | 15505 |
