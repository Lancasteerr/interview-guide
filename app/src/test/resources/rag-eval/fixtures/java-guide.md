# Java 后端核心知识手册（测评专用）

本文档为 RAG 测评人工编写的虚构知识库，内容与任何真实项目无关。

## JVM 垃圾回收

JVM 垃圾回收器的发展经历了 Serial、Parallel、CMS、G1、ZGC 几代。G1 回收器把堆划分为多个等大的 Region，默认期望停顿目标是 200 毫秒。ZGC 是面向低延迟的回收器，停顿时间不超过 1 毫秒，支持 TB 级堆。

垃圾回收的判断使用可达性分析算法，从 GC Roots 出发不可达的对象可以被回收。GC Roots 包括虚拟机栈中引用的对象、方法区中类静态属性引用的对象、本地方法栈中 JNI 引用的对象。

对象分配优先在新生代 Eden 区进行，Eden 不足时触发 Minor GC。大对象直接进入老年代，可通过 `-XX:PretenureSizeThreshold` 控制。经历多次 Minor GC 仍存活的对象通过年龄晋升机制进入老年代，默认晋升年龄是 15。

## HashMap 与 ConcurrentHashMap

HashMap 在 JDK 8 之后的底层结构是数组加链表加红黑树。当链表长度超过 8 且数组容量达到 64 时，链表转换为红黑树；红黑树节点数收缩到 6 时退化回链表。

HashMap 的容量总是 2 的整数次幂，这样 hash 值对容量取模可以转化为位运算 `(n - 1) & hash`。默认初始容量是 16，负载因子是 0.75，元素数量超过容量乘以负载因子时触发扩容，扩容为原来的两倍。

ConcurrentHashMap 在 JDK 8 中放弃了分段锁，改用 CAS 加 synchronized 锁住单个桶的头节点。size 统计使用 baseCount 加 CounterCell 分散计数的方式实现。

## 线程池

线程池的核心参数一共有七个：corePoolSize、maximumPoolSize、keepAliveTime、unit、workQueue、threadFactory、handler。任务提交后，线程数小于 corePoolSize 时直接创建核心线程；核心线程满后任务进入工作队列；队列满后创建非核心线程直至 maximumPoolSize；再满则触发拒绝策略。

内置的四种拒绝策略分别是：AbortPolicy 抛出异常、CallerRunsPolicy 由提交线程执行、DiscardPolicy 静默丢弃、DiscardOldestPolicy 丢弃队首最老任务。生产环境建议使用自定义拒绝策略配合监控告警，而不是默认的 AbortPolicy。

线程池的线程数配置没有万能公式。CPU 密集型任务建议线程数等于 CPU 核数加一；IO 密集型任务建议线程数为 CPU 核数乘以二，或按 IO 等待比例用公式核数乘以（一加 IO 时间除以 CPU 时间）估算。

## MySQL 索引

InnoDB 的索引组织结构是 B+ 树，数据行本身存储在主键索引的叶子节点上，这种表称为索引组织表。二级索引的叶子节点存储主键值而不是行地址，因此通过二级索引查询完整行需要回表。

最左前缀原则要求联合索引只能按定义顺序从最左列开始匹配。索引下推是 MySQL 5.6 引入的优化，在存储引擎层先过滤联合索引中包含的条件下推列，减少回表次数。

## Redis 基础

Redis 的持久化方式包括 RDB 快照和 AOF 日志。RDB 是某一时刻的全量二进制快照，恢复快但可能丢失最后一次快照后的数据。AOF 记录每条写命令，通过 appendfsync 策略控制刷盘，策略有 always、everysec、no 三种，默认 everysec 表示每秒刷盘一次。

Redis 单线程处理命令请求，但 IO 读写和网络协议解析在 6.0 之后支持多线程。缓存穿透的常见解决方案是布隆过滤器和空值缓存；缓存击穿的解决方案是互斥锁和逻辑过期；缓存雪崩的解决方案是过期时间加随机偏移和多级缓存。

## Spring 核心

Spring Bean 的生命周期可以概括为：实例化、属性注入、初始化前处理、初始化（InitializingBean 的 afterPropertiesSet 或 init-method）、初始化后处理、使用、销毁。BeanPostProcessor 的 postProcessAfterInitialization 是 AOP 生成代理对象的常见时机。

Spring 事务失效的常见场景包括：同类内部方法调用绕过代理、方法不是 public、异常被 catch 吞掉、默认只回滚 RuntimeException 和 Error。解决办法分别是通过代理对象调用、改用编程式事务、显式回滚或配置 rollbackFor。

Spring Boot 自动装配依靠 `@EnableAutoConfiguration` 导入的自动配置类，配合 `@ConditionalOnClass`、`@ConditionalOnMissingBean` 等条件注解按需生效。自定义 Starter 的关键是把自动配置类注册到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件中。

## 网络与协议

TCP 三次握手的目的是同步双方初始序列号并确认彼此收发能力。TIME_WAIT 状态持续两倍最大报文段寿命，即 2MSL，目的是确保最后一个 ACK 能到达对端并让旧报文在网络中消亡。

HTTP/2 相比 HTTP/1.1 的核心改进是二进制分帧、多路复用、头部压缩 HPACK 和服务器推送。多路复用解决了 HTTP/1.1 队头阻塞在连接层面的问题，但 TCP 层的队头阻塞仍然存在，直到 HTTP/3 换用 QUIC 基于 UDP 才解决。

## 分布式基础

CAP 定理指出分布式系统在一致性、可用性、分区容错性三者中最多同时满足两个。网络分区不可避免，因此实际的取舍是在 CP 和 AP 之间。ZooKeeper 偏向 CP，Eureka 偏向 AP。

分布式锁的常见实现有基于 Redis 的 SET NX EX 和基于 ZooKeeper 的临时顺序节点。Redis 分布式锁的续期问题可以用看门狗机制解决，RedLock 算法因为时钟漂移等问题存在争议，多数业务场景用单实例锁加 fencing token 即可。
