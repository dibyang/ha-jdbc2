# HA-JDBC2 现存代码问题审查报告（2026-08-26）

## 1. 结论

本轮在当前工作区代码上确认 **4 项 P0、12 项 P1、4 项 P2**。最优先处理的不是日志或代码风格，而是事务锁、同步恢复和凭据安全：

1. 手工事务的集群读锁在每条语句后释放，数据库激活可插入未提交事务并造成新节点缺数。
2. 共享读锁的一次 `unlock()` 会释放全部读者，数据库同步可能与仍在运行的事务并发。
3. H2 dump/restore 存在“未执行或失败但同步返回成功”的路径，旧库、空库可能被激活。
4. MySQL dump/restore 将明文密码放入进程参数，并可能写入 DEBUG 日志。

现有 JDK 8 测试门禁为绿色，但事务锁、分布式锁、差异同步、dump/restore、`pool/sql` 等关键路径基本没有契约测试，不能据此判定这些风险不存在。

## 2. 扫描范围与方法

- 构建与 CI：`build.gradle`、`gradle.properties`、Gradle Wrapper、`.gitlab-ci.yml`。
- 生产代码：`src/main/java/net/sf/hajdbc/**`。
- 测试代码：`src/test/java/**`。
- 设计和既有问题稿：`docs/design.md`、`docs/problem-analysis-and-solution-discussion.md`。
- 检查维度：事务与锁、节点激活/同步、JDBC 代理、资源生命周期、线程与超时、分布式 RPC、文件边界、凭据、JDK/Gradle 兼容性。

本报告只记录当前代码仍然存在的问题。既有问题稿中已经完成的测试门禁恢复、`isValid()` 语义修正、启动超时、健康线程池关闭、路径集中配置等内容不重复计为新问题。

### 2.1 验证基线

| 环境/命令 | 结果 |
| --- | --- |
| JDK 8 + `gradlew.bat test --console=plain` | 成功；515 项测试，513 通过、0 失败、2 跳过 |
| JDK 17 + Gradle Wrapper 6.1 | Groovy 初始化失败：`InvokerHelper` / `ReflectionCache` |
| JDK 17 + 本机 Gradle 7.6.6 | 依赖下载后进入编译，但因缺少 `javax.xml.bind` 失败 |

测试期间没有修改生产代码。当前工作区原有未提交改动均予以保留。

## 3. 严重度说明

| 级别 | 定义 |
| --- | --- |
| P0 | 可直接导致数据丢失/错误激活、重大安全泄露或集群级不可接受后果 |
| P1 | 高概率生产故障、长期阻塞、状态分叉、资源耗尽或核心功能不可用 |
| P2 | 有明确触发条件的可靠性、兼容性、可观测性或维护风险 |
| P3 | 低风险维护问题；本报告不展开一般风格问题 |

## 4. 问题总览

| 编号 | 级别 | 问题 | 首要位置 |
| --- | --- | --- | --- |
| CR-P0-01 | P0 | 手工事务未持有事务级集群读锁 | `LocalTransactionContext.java:60-107, 134-152` |
| CR-P0-02 | P0 | 一个读者释放全部共享读锁 | `SemaphoreLock.java:84-96`、`NodeReadLock.java:48-56` |
| CR-P0-03 | P0 | H2 恢复跳过/失败被当成同步成功 | `H2Dialect.java:196-217` |
| CR-P0-04 | P0 | MySQL 明文密码进入 argv 和日志 | `MySQLDialect.java:214-253`、`Processes.java:41-43` |
| CR-P1-01 | P1 | 同步文件边界默认放行，块大小可触发大内存分配 | `SyncFilePath.java:41-47`、`DownloadCommand.java:44-65` |
| CR-P1-02 | P1 | 差异同步 `versionPattern` 路径读取错误数据库且未移动游标 | `DifferentialSynchronizationStrategy.java:221-225, 349-365, 422-440` |
| CR-P1-03 | P1 | 按表同步失败后约束不恢复，目标库处于半同步状态 | `PerTableSynchronizationStrategy.java:65-88` |
| CR-P1-04 | P1 | JDBC 对象状态重放失败被吞，半初始化对象仍被缓存 | `AbstractProxyFactory.java:121-138, 159-175` |
| CR-P1-05 | P1 | 无活动数据库异常被创建后丢弃 | `AllResultsCollector.java:67-92` |
| CR-P1-06 | P1 | 分布式批量 RPC 将异常伪装为 `null`，可残留远端锁或丢状态 | `JGroupsCommandDispatcher.java:161-180`、`DistributedLock.java:159-185` |
| CR-P1-07 | P1 | dump/restore 子进程可能管道死锁且无超时 | `Processes.java:51-85` |
| CR-P1-08 | P1 | JDBC 多节点调用使用无界线程池和无界等待 | `DefaultExecutorServiceProvider.java:40-43`、`AllResultsCollector.java:89-103` |
| CR-P1-09 | P1 | 同步上下文构造失败泄漏连接和线程池 | `SynchronizationContextImpl.java:63-98` |
| CR-P1-10 | P1 | 健康心跳修改整个 JVM 的 Joda 全局时钟并产生振荡 | `ClusterHealthImpl.java:160-180` |
| CR-P1-11 | P1 | 健康状态跨线程读写缺少可见性和原子状态迁移 | `ClusterHealthImpl.java:64, 184-207` |
| CR-P1-12 | P1 | `pool/sql` 发布了不可用的空实现 | `pool/sql/PoolingDriver.java`、`DriverPooledConnection.java` |
| CR-P2-01 | P2 | `TokenStore` 静态非守护线程池无生命周期且队列无界 | `TokenStore.java:20`、`TimeoutUtil.java:27-31` |
| CR-P2-02 | P2 | 可选健康观察器实际从不执行 | `Observer.java:45-69` |
| CR-P2-03 | P2 | 构建基线仅在 JDK 8 可用，JDK 17 门禁未定义 | `gradle-wrapper.properties:3`、`build.gradle:60-75` |
| CR-P2-04 | P2 | GitLab CI 使用 Maven，但仓库只有 Gradle | `.gitlab-ci.yml:1-20` |

## 5. 详细问题

### CR-P0-01 手工事务的集群读锁在每条语句后提前释放

**证据**

- `LocalTransactionContext.start(...)` 在 `autoCommit=false` 时于 `LocalTransactionContext.java:92` 加锁。
- 同一次语句调用在 `LocalTransactionContext.java:102-105` 的 `finally` 中无条件解锁。
- `unlock()` 同时在 `LocalTransactionContext.java:188-191` 清空 `transactionId`。
- `commit/rollback` 进入 `end(...)` 时，如果 `transactionId == null`，会在 `LocalTransactionContext.java:136` 直接返回原策略，不再持有事务锁或记录 durability 阶段。
- 数据库激活/同步使用全局写锁，入口位于 `DatabaseClusterImpl.java:1106-1143`。

**失败场景**

连接关闭自动提交后执行 DML，DML 已写入原有节点但尚未提交。语句返回后读锁被释放；激活线程取得全局写锁，通过新的数据库连接做同步，因此看不到该未提交写入。目标节点被激活后，业务连接再执行 `commit()`，新节点上没有对应 DML，最终形成永久缺数。

**影响**

主节点切换或原节点故障后可表现为已提交数据丢失，属于 P0 数据一致性问题。

**最小修复**

- 手工事务首次写操作时创建事务 ID 并加一次读锁，直到 `commit`、`rollback`、`setAutoCommit(true)` 或 `close` 才释放。
- 用明确状态机防止重复加锁/重复释放。
- 增加“DML 已完成但 commit 未发生时并发 activate”的确定性测试。

### CR-P0-02 一个读者的 `unlock()` 会释放全部共享读锁

**证据**

- `SemaphoreLock.unlock()` 在 `SemaphoreLock.java:86-96` 循环把共享 `readCount` 降到 0，并为每一层调用 `semaphore.release()`。
- `NodeReadLock.unlock()` 在 `NodeReadLock.java:49-56` 使用相同的清空循环。
- `DistributedLockManager.readLock()` 会复用同一 `(member, id, READ)` 的 `NodeReadLock`。

**失败场景**

事务 T1、T2 同时持有同一全局读锁，计数为 2。T1 调用一次 `unlock()` 后循环释放两个 permit；数据库激活线程取得写锁进入同步，而 T2 仍在修改数据库。

**影响**

同步快照与在途事务交叠，破坏激活期间“无并发写”的核心约束。即使修复 CR-P0-01，本问题仍会使另一个事务的锁被提前释放。

**最小修复**

每次成功 `lock()` 只允许一次对应的 `unlock()`：计数只减 1、permit 只释放 1。非法释放应抛出明确异常。补充“两读者一写者”的并发回归测试。

### CR-P0-03 H2 restore 未执行或失败时仍可能激活目标库

**证据**

- `H2Dialect.restore()` 仅在 `!database.isLocal()` 时执行，见 `H2Dialect.java:196-217`；本地目标直接正常返回。
- `syncMgr.upload(...)` 返回 `false` 时方法同样正常返回，见 `H2Dialect.java:202-216`。
- `syncMgr.execute(target, cmd)` 的 `Boolean` 结果未检查，见 `H2Dialect.java:203-212`。
- `H2RunScriptCommand2.execute()` 在异常时记录后返回 `false`，见 `H2RunScriptCommand2.java:33-48`。
- `DumpRestoreSynchronizationStrategy.synchronize()` 只依赖 `restore()` 是否抛异常判断成功，见 `DumpRestoreSynchronizationStrategy.java:90-109`。

**失败场景**

本地 H2 目标根本没有恢复，或远端上传/执行失败并返回 `false`；同步策略仍正常结束，`DatabaseClusterImpl.activate()` 随后把旧数据、空数据或部分恢复的节点加入活动集。

**影响**

读请求可立即读到旧数据，写复制会建立在错误基线之上；切换后可造成业务数据缺失。

**最小修复**

实现本地恢复路径；上传失败、目标成员不存在、命令结果非 `Boolean.TRUE` 均必须抛出同步失败；激活前增加 schema/数据校验。破坏性恢复失败的节点必须保持 inactive。

### CR-P0-04 MySQL 明文密码进入进程参数和日志

**证据**

- `MySQLDialect.java:214-220`、`235-244` 构造 `--password=<明文>` 参数。
- `Processes.java:41-43` 在 DEBUG 级别记录完整命令列表。
- `MySQLDialect.java:247-253` 还同时设置 `MYSQL_PWD`。

**失败场景与影响**

启用 DEBUG 后密码进入日志；即使关闭日志，argv 仍可能被同机进程、诊断工具或进程快照读取。数据库高权限凭据泄露属于 P0 安全问题。

**最小修复**

使用权限受限的临时 `defaults-extra-file` 或目标平台安全凭据机制，禁止密码进入 argv；命令日志按参数名强制脱敏；临时文件在 `finally` 中删除。

### CR-P1-01 文件同步仍是默认开放边界

**证据**

- `SyncFilePath.java:41-47` 在 `ha-jdbc.sync.allowed-roots` 未配置时直接放行任意规范化路径。
- `DownloadCommand.java:53-65` 读取命令携带的路径。
- `UploadCommand.java:48-57` 和 `UploadedCommand.java:64-85` 可写临时文件并替换目标。
- `DownloadCommand.java:44-45,64` 接受远端 `blockSize` 并直接创建相应大小的 `byte[]`。

**影响**

一旦集群成员被入侵、混入非可信成员或命令边界被绕过，可读取/覆盖服务账号有权访问的文件；超大 `blockSize` 还可触发堆内存耗尽。当前“可选白名单”降低了已配置部署的风险，但默认值不是安全默认值。

**最小修复**

未配置允许根目录时拒绝同步；协议传文件 ID/相对路径，由服务端解析实际路径；校验真实父目录并拒绝符号链接逃逸；为 `offset`、`blockSize`、总长度设置硬上限。

### CR-P1-02 差异同步的版本列优化路径不可用

**证据**

- `selectAllStatement` 由 `targetConnection` 创建，见 `DifferentialSynchronizationStrategy.java:221-225`。
- 插入分支查询目标库中本来不存在的源 PK，并在未调用 `ResultSet.next()` 时直接 `getObject()`，见 `:338-365`。
- 更新分支同样未移动游标，且即使补 `next()`，仍会从目标旧值回填目标，见 `:411-440`。
- 用户文档示例明确配置 `versionPattern`，见 `src/site/markdown/doc.md:331-334`。

**影响**

启用 `versionPattern` 后，新增行通常直接同步失败；更新行即便规避游标异常，也不能把源端非版本列复制到目标。

**最小修复**

由 `sourceConnection` 创建查询；每次执行后显式校验 `resultSet.next()`，缺行立即失败；每个临时结果集显式关闭。为新增、更新、删除和空结果补集成测试。

### CR-P1-03 按表同步失败不是原子的

**证据**

- `PerTableSynchronizationStrategy.java:65` 在主同步前删除外键。
- `:67-68` 之后才关闭自动提交。
- 每张表在 `:76` 单独提交。
- 异常只回滚当前表，见 `:78-82`。
- `restoreConstraints()` 位于正常路径 `:85`，不在 `finally`。

**影响**

第三张表失败时，前两张表已经提交，后续表仍是旧数据，外键也不会恢复。目标库虽未激活，但已经成为需要人工修复的半同步数据库；自动重试还可能放大破坏。

**最小修复**

约束恢复必须放入保留原始异常的 `finally`。同步前预检表、主键和方言能力。对 DDL 隐式提交的数据库，完整失败原子性需采用 staging/影子库和原子切换，不能只依赖事务回滚。

### CR-P1-04 JDBC 对象状态重放失败被吞

**证据**

- `AbstractProxyFactory.get()` 创建对象、调用 `replay()` 后放入 map，见 `AbstractProxyFactory.java:121-128`。
- `replay()` 捕获异常后只构造异常对象但没有 `throw`，见 `:167-174`。

**影响**

新节点重放 `setAutoCommit(false)`、事务隔离级别或 Statement 参数失败时，半初始化对象仍参与后续调用。例如真实连接保持自动提交，业务以为可回滚的写入已经独立提交。

**最小修复**

抛出 `exceptionFactory.createException(e)`；只有完整重放成功后才加入 map；失败时关闭新对象并沿用外层既有的节点停用/告警路径。

### CR-P1-05 无活动数据库时返回空结果路径

**证据**

`AllResultsCollector.java:75-78` 调用了 `exceptionFactory.createException(...)`，但没有抛出或保存返回的异常，之后继续对空任务列表执行 `invokeAll()` 并返回空结果。

**影响**

调用方得到空映射、后续位置异常或类似成功的默认结果，而不是明确的“无活动数据库”错误。错误类型取决于上层结果处理，增加误判和恢复难度。

**最小修复**

调整 collector 接口允许抛出入口对应异常，或抛出带 `NO_ACTIVE_DATABASES` 上下文的领域异常；补空 balancer 测试。

### CR-P1-06 分布式批量 RPC 丢失失败语义

**证据**

- `JGroupsCommandDispatcher.executeAll()` 在 `JGroupsCommandDispatcher.java:161-180` 捕获异常后返回 `null`，且没有逐项检查远端异常、超时或 suspect 状态。
- `DistributedLock.lockMembers()` 在 `DistributedLock.java:159-174` 直接遍历结果；发生 `null` 时补偿 `unlockMembers()` 不能可靠执行。
- 活跃库、durability 等关键状态广播也通过同一路径执行。

**失败场景**

锁 acquire 已被成员 B 执行，但响应链在发起方异常；dispatcher 返回 `null`，发起方在处理结果时失败，没有对 B 做确定性补偿。若成员视图未移除发起方，B 保留僵尸锁。滚动升级时远端反序列化/命令不存在也可能被伪装为合法 `null`，造成节点状态分叉。

**最小修复**

批量 RPC 返回逐成员结构化状态并保留 cause；关键广播非全成功必须失败。锁协议引入 request ID、幂等 release 和租约；失败路径对所有“可能成功”的成员做补偿。增加部分送达、超时、成员离开和新旧版本命令测试。

### CR-P1-07 外部进程可能永久死锁

**证据**

- `Processes.java:73-85` 先 `process.waitFor()`，进程退出后才读取合并的 stdout/stderr。
- 带输入重载在 `Processes.java:51-68` 写入后未可靠关闭 stdin 以发送 EOF。
- 全路径没有进程超时。
- MySQL/PostgreSQL dump/restore 均调用该工具。

**影响**

子进程输出填满管道后等待父进程读取，父进程等待子进程退出，形成永久死锁；该操作处于激活同步写锁范围内，可长期阻塞集群维护和业务事务。

**最小修复**

启动后立即并发排空输出或重定向到受控文件；写入后关闭 stdin；使用 Java 8 `waitFor(timeout, unit)`，超时后 `destroy()`/`destroyForcibly()` 并保留截断后的输出摘要。

### CR-P1-08 多节点 JDBC 调用无背压、无截止时间

**证据**

- 默认执行器为 `Executors.newCachedThreadPool()`，见 `DefaultExecutorServiceProvider.java:40-43`。
- `AllResultsCollector.java:89-103` 使用无超时的 `invokeAll()` 和 `Future.get()`。
- 全量/差异同步也使用无超时 `Future.get()`。

**影响**

数据库变慢或驱动卡住时，并发请求持续创建线程；单个失联驱动可让事务读锁或激活写锁无限持有，最终产生 native thread/OOM、请求堆积和集群维护停顿。

**最小修复**

使用按连接容量配置的有界线程池和有界队列；为 JDBC network/query、批量调用和同步设置统一截止时间；超时后取消任务并把“写结果未知”的节点置为不确定/停用，禁止盲目重试非幂等写入。

### CR-P1-09 同步上下文构造失败泄漏资源

**证据**

- `SynchronizationContextImpl.java:63-79` 先创建固定线程池，再依次获取目标/源连接和元数据。
- 任一步抛异常时构造器没有清理逻辑，对象也无法返回给外层调用 `close()`。
- `getConnection()` 在 `:91-94` 中先创建连接，再调用 `getAutoCommit()`；后者失败时连接尚未进入 map。

**影响**

自动激活反复重试可累积连接、数据库会话和线程，直至耗尽连接池或系统资源。

**最小修复**

把构造改为带局部资源和成功标记的静态 `open()` 工厂；任何初始化失败都关闭刚创建及 map 内已有连接，并 `shutdownNow()` executor。

### CR-P1-10 心跳校时修改宿主 JVM 全局时间

**证据**

`ClusterHealthImpl.receiveHeartbeat()` 使用 `new DateTime()` 计算偏移后调用 `DateTimeUtils.setCurrentMillisOffset(offsetTime)`，见 `ClusterHealthImpl.java:160-167`；`getHostTime()` 再读取已被修改的 Joda 当前时间，见 `:177-180`。

**失败场景**

主机时钟比备机快 10 秒：第一拍设置 +10 秒；第二拍的 `new DateTime()` 已包含 +10 秒，于是计算接近 0 并清零；第三拍再次设置 +10 秒，产生两拍振荡。`DateTimeUtils` 是 JVM 静态全局，宿主应用中所有 Joda 时间都会被改变。

**影响**

健康逻辑时间不稳定，并污染同 JVM 业务的超时、审计和时间计算。

**最小修复**

始终以 `System.currentTimeMillis()` 计算私有 offset；`getHostTime()` 返回 wall clock + 私有 offset；禁止调用 Joda 全局时间 setter。加入 RTT、偏差上限和平滑策略。

### CR-P1-11 健康状态机跨线程读写不安全

**证据**

- `ClusterHealthImpl.state` 在 `ClusterHealthImpl.java:64` 既非 `volatile`，也不受统一锁保护。
- `getState/isHost/canWrite/setState` 位于 `:184-207`，由健康调度线程、JGroups 命令线程和业务线程调用。
- `setState()` 的比较、赋值和 `changeState()` 副作用不是原子状态迁移。

**影响**

线程可能读取旧 host/ready/offline 状态，继续发送心跳、激活/停用数据库或参与选举；并发迁移还可能重复/乱序执行副作用，形成短暂双主或错误写权限。

**最小修复**

最低限度把状态声明为 `volatile`；推荐把所有状态事件投递到单线程状态机，并用 CAS/同步保护“校验合法迁移—更新状态—执行副作用”的完整过程。

### CR-P1-12 `pool/sql` 是公开但不可用的空实现

**证据**

- `pool/sql/PoolingDriver.java:58-63` 对其声明处理的 URL 返回 `null`。
- `DriverPooledConnection.java:34-38, 67-81` 丢弃 factory，`getConnection()` 返回 `null`，`close()` 和 listener 方法为空。
- `DriverConnectionPoolDataSource.java:79-126` 的超时和 log writer 方法为空实现。
- 测试树没有 `pool/sql` 契约测试。

**影响**

显式使用 `jdbc:ha-jdbc:pool:` 或 `ConnectionPoolDataSource` 时核心 API 不可用，并可能表现为 `no suitable driver`、空连接或资源/监听器泄漏；全量测试仍会绿色。

**最小修复**

若功能尚未支持，从发布 API/SPI 入口移除并明确抛 `UnsupportedOperationException`；若承诺支持，完整实现 JDBC 契约并增加 Driver/PooledConnection/CPDS 契约测试。

## 6. P2 问题

### CR-P2-01 `TokenStore` 静态线程池没有生命周期

`TokenStore.java:20` 持有静态 `TimeoutUtil`；`TimeoutUtil.java:27-31` 使用固定核心线程和无界 `LinkedBlockingQueue`；线程工厂创建非守护线程。仓库没有关闭该静态池的调用。首次 token I/O 后，集群 `stop()` 仍可能留下线程并阻止 JVM/容器退出；共享文件系统卡顿时任务还会持续积压。

建议将线程池归属到 cluster 生命周期，在 stop 时 `shutdownNow + awaitTermination`，并使用有界队列、拒绝策略和隔离/熔断。

### CR-P2-02 可选观察器永远不会被执行

`Observer.java:56` 正确生成 `optionalAdapters`，但 `Observer.java:58` 又遍历了 `mustAdapters`。因此 Ping/Connect 等可选观察器结果不参与判定，定制 SPI 配置形同虚设。

建议改为遍历 `optionalAdapters`，并明确“必选全部通过 + 可选至少一个通过”的语义，覆盖必选失败、可选全失败、可选一个成功三个测试。

### CR-P2-03 JDK 兼容范围没有形成可执行合同

项目声明 `source/target=1.8`，Wrapper 固定 Gradle 6.1。实测 JDK 8 全量测试通过；默认 JDK 17 无法启动 Wrapper，换用兼容 JDK 17 的 Gradle 后又因 JAXB 未声明而编译失败。

建议二选一：

- 明确只支持 JDK 8，并在 Wrapper/CI 启动前给出清晰版本校验；或
- 升级 Wrapper，配置 Java toolchain/`--release 8`，显式加入 JAXB API/runtime，并建立 JDK 8/17 矩阵。

### CR-P2-04 GitLab CI 与构建系统不匹配

`.gitlab-ci.yml` 使用 Maven JDK 8 镜像并执行 `mvn`，但仓库没有 `pom.xml`，只有 Gradle 构建。该流水线在干净环境会直接失败。同时 `build.gradle:213-220` 在普通配置阶段读取仓库地址，而仓库内 `gradle.properties` 没有这些属性；本机成功依赖用户级 Gradle 配置，干净 CI 仍可能失败。

建议改为 JDK 8 Gradle/Wrapper 任务，至少执行 `./gradlew --no-daemon clean test`；发布仓库属性只在 publish 任务真正执行时校验，普通编译测试不依赖私有发布配置。

## 7. 测试覆盖缺口

本轮高风险问题未被 515 项现有测试捕获，主要缺口如下：

| 领域 | 最小新增测试 |
| --- | --- |
| 手工事务 | DML 后、commit 前并发 activate；commit/rollback/close 仅释放一次锁 |
| 共享锁 | 两读者并发持锁，一个释放后写者仍不可进入 |
| H2 restore | 本地恢复、上传失败、命令返回 false、恢复后校验失败均禁止激活 |
| 差异同步 | `versionPattern` 下新增/更新/删除及空结果 |
| JDBC 代理 | replay 任一步失败时对象不入 map、节点停用、资源关闭 |
| 分布式 RPC | 部分送达、超时、远端异常、成员离开、混合版本命令 |
| 外部进程 | 大量输出、stdin EOF、超时销毁、日志脱敏 |
| 连接池 | Driver、PooledConnection、CPDS 标准契约 |
| 健康状态 | 并发状态迁移、心跳偏移不修改 JVM 全局时间、可选 observer 生效 |

## 8. 建议治理顺序

### 阶段 A：立即止血

1. 修复 CR-P0-01、CR-P0-02，并增加事务/锁并发门禁。
2. 在修复 CR-P0-03 前，禁止使用 H2 dump/restore 自动激活。
3. 立即清除 MySQL argv/日志中的密码；检查历史日志是否需要轮换和凭据轮换。
4. 将同步路径改为默认拒绝，并限制块大小。

### 阶段 B：恢复同步与代理正确性

1. 修复差异同步版本列路径和按表同步失败恢复。
2. 修复代理 replay 和无活动数据库异常。
3. 为 dump/restore 子进程增加输出消费、EOF 和超时。

### 阶段 C：分布式可靠性与资源治理

1. 重构批量 RPC 失败模型和分布式锁补偿协议。
2. 引入有界执行器、统一截止时间、JDBC network/query timeout。
3. 修复同步构造失败清理、TokenStore 生命周期和健康状态机。

### 阶段 D：构建与产品边界

1. 决定 `pool/sql` 是实现还是下线。
2. 明确 JDK 支持矩阵，修正 GitLab CI 和干净环境构建。

## 9. 残余风险

- 本轮是静态调用链审查加单 JVM 测试门禁，没有完成双/三节点 JGroups 故障注入。
- 没有连接真实 MySQL/PostgreSQL 执行 dump/restore，也没有验证各数据库 DDL 事务语义。
- 未执行依赖漏洞数据库扫描；旧版 JGroups、数据库驱动和构建插件的安全状态需要单独做联网供应链审查。
- 工作区包含在途未提交改动，本报告描述的是扫描时的当前工作区状态，不等同于某个 Git commit 的不可变快照。
