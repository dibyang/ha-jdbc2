# HA-JDBC2 当前问题扫描与解决方案讨论稿

## 背景

本稿基于当前仓库代码扫描、关键路径阅读和轻量构建验证形成，目标是在不直接修改生产代码的前提下，先把已发现问题、影响范围、候选解决方案和验证方式摆出来，供后续评审确认。

已执行验证：

- `.\gradlew.bat compileJava`：通过。
- `.\gradlew.bat test --dry-run --console=plain`：构建成功，但 `compileTestJava`、`testClasses`、`test` 等任务均显示 `SKIPPED`。

## 目标

- 找出当前项目中影响可测试性、运行稳定性、分布式一致性、部署兼容性和发布合规性的明显问题。
- 对每个问题给出不止一种处理路径，方便后续按风险和成本决策。
- 明确哪些问题是事实确认，哪些是基于代码语义的推断，哪些仍需运行环境或业务约束确认。

## 非目标

- 本稿不修改 Java 代码、构建脚本或发布配置。
- 本稿不承诺所有候选方案都应一次性落地。
- 本稿不替代生产环境压测、故障演练或合规审查。

## 问题总览

| 编号 | 严重级别 | 问题 | 主要证据 | 建议优先级 |
| --- | --- | --- | --- | --- |
| P0-1 | 已处理 | Gradle 全局禁用测试任务，CI 容易出现“绿灯但未测试” | 原 `build.gradle:10-15`, `test --dry-run` 全部测试相关任务 skipped | 已改为默认启用测试，跳过需显式 `-PskipTests=true` |
| P0-2 | 已处理 | 状态管理器 `isValid()` 与激活流程语义冲突，可能阻止手动激活非活跃数据库 | `DatabaseClusterImpl:1033-1037`, `SimpleStateManager:100-102`, `SQLStateManager:584-586` | 已明确本地状态管理器 `isValid()` 不等同于 active |
| P0-3 | 已处理 | 运行库代码在健康启动失败时直接 `System.exit(1)` | `ClusterHealthImpl:110-115` | 已改为向调用方抛出启动失败异常 |
| P1-1 | 已处理 | 集群启动存在无限等待路径，配置或环境错误会挂住调用线程 | `DatabaseClusterImpl:562-573`, `DatabaseClusterImpl:850-853` | 非分布式启动恢复已修复；本地库发现与活动库等待均已加入可配置超时 |
| P1-2 | 已处理 | 主备健康逻辑中用 IP 调 `getDatabase(id)`，数据库 ID 与 IP 不一致时可能抛异常 | `ClusterHealthImpl:463-491`, `DatabaseClusterImpl:531-557` | 已改为通过 `getDatabaseByIp()` 查找主节点数据库 |
| P1-3 | 已处理（短期） | 分布式文件同步下载可能保留旧文件尾部，上传/下载命令缺少路径信任边界 | `SyncMgrImpl:82-155`, `UploadCommand:46-62`, `UploadedCommand:60-99`, `DownloadCommand:49-75` | 已改为下载临时文件替换、失败清理临时文件，并增加同步路径白名单 |
| P1-4 | 已处理 | 发布元数据许可证与仓库 LICENSE/README 不一致 | `LICENSE:1-2`, `README.md:5-7`, `build.gradle:169-173` | 已统一为 LGPL |
| P2-1 | 中 | 健康检测和诊断配置硬编码 Linux 系统路径 | `ClusterHealthImpl:70`, `ClusterHealthImpl:100`, `Tracer:18-25`, `FileReader:62-66` | 中 |
| P2-2 | 已处理 | `TimeoutUtil` 忽略调用方传入的 `TimeUnit` | `TimeoutUtil:59-77` | 已改为使用调用方传入的时间单位，并增加秒级超时与取消测试 |
| P2-3 | 中 | 资源关闭不完整，健康检测线程池未在 `stop()` 中关闭 | `ClusterHealthImpl:92-121`, `ClusterHealthImpl:721-726` | 中 |
| P2-4 | 中 | 日志体系混用和裸 `printStackTrace()`，生产问题难以统一收敛 | `ClusterHealthImpl:773-787`, `ClusterHealthImpl:847-855`, `ZipUtils` 等 | 中 |
| P3-1 | 低中 | 空 SPI 文件可能造成扩展点配置误导 | `META-INF/services/net.sf.hajdbc.state.health.observer.ObserveAdapter` 长度为 0 | 低中 |

## 现状/已有流程

### 构建与测试

原 `build.gradle` 在 `gradle.taskGraph.whenReady` 中遍历所有任务，只要任务名包含 `"test"` 就设置 `task.enabled = false`。这会影响默认测试门禁，使 CI 或本地验证容易出现“构建成功但未运行测试”的错觉。

当前处理：已取消按任务名全局禁用测试，测试默认启用；如果确实需要临时跳过测试，必须显式传入 `-PskipTests=true`。`test` 默认设置 120 秒超时，避免历史测试挂起时形成无期限等待；确有长时间验证需要时，可通过 `-PtestTimeoutSeconds=<seconds>` 显式调整。同时保留 `verifyTestCompilation` 作为“只编译测试源码、不运行测试”的轻量入口。

恢复测试门禁后，`net.sf.hajdbc.sql.BlobTest` 曾暴露启动恢复挂起问题；当前已修复本地状态管理器有效性语义、DataSource 数据库类型识别和非分布式启动激活路径，`BlobTest` 与全量 `test` 已可完成。

### 集群激活

`DatabaseClusterImpl.activate(D, SynchronizationStrategy)` 在真正同步和加入 balancer 前先做：

```java
if (!this.isAlive(database, Level.INFO) || !stateManager.isValid(database)) {
    return false;
}
```

原多个本地持久化状态管理器的 `isValid(Database<?>)` 直接返回 `getActiveDatabases().contains(database.getId())`。这意味着“非活跃数据库”在进入激活流程前就可能被判定无效，形成语义闭环：未激活所以无效，无效所以不能激活。当前已将本地状态管理器的 `isValid()` 明确为拓扑有效性判断，不再等同于 active 集合判断；分布式状态管理器继续按成员 IP 判断。

### 健康检测与主备

`ClusterHealthImpl.start()` 会先同步执行一次 `doTask()`，遇到 `StartFailException` 时直接 `System.exit(1)`。健康检测还依赖本地 IP、JGroups member IP、`/proc/mounts`、`/etc/aio/.mfs_ip`、`/etc/ha-jdbc/*` 等运行环境。

### 分布式文件同步

`SyncMgrImpl.download()` 使用 `RandomAccessFile(file, "rws")` 按块写入目标文件，但没有在下载开始前清空或截断本地旧文件。若目标文件已存在且比远端文件更大，下载完成后可能留下旧尾部。上传侧 `UploadCommand` 和 `UploadedCommand` 使用调用方传入的 path 拼接 `.tmp` 并移动到最终路径，当前代码没有限定目录、规范化路径或校验路径来源。

## 核心约束

- 该项目是 JDBC 代理库，不能由库代码随意终止宿主 JVM。
- HA 场景中“活跃状态”“成员有效性”“数据库可连接性”是不同概念，修复时不能混成一个布尔值。
- 分布式状态、锁、文件同步和健康选主属于高风险路径，修复需要故障注入或最小多节点验证。
- 当前主代码可编译，但测试门禁无效；任何生产代码修复都应先恢复可执行测试。
- 项目当前面向 JDK 8，候选方案需要保持 JDK 8 兼容。

## 详细问题与候选方案

### P0-1 Gradle 全局禁用测试任务

**事实证据**

- `build.gradle:10-15` 对所有名称包含 `"test"` 的任务设置 `enabled = false`。
- `.\gradlew.bat test --dry-run --console=plain` 显示 `compileTestJava`、`testClasses`、`test` 全部 `SKIPPED`。

**影响**

- CI、发布门禁、本地验证可能误判为通过。
- 测试代码即使无法编译也不会暴露。
- 后续修改分布式、状态机、锁等高风险逻辑时缺少基本保护。

**候选方案**

| 方案 | 内容 | 优点 | 风险/成本 |
| --- | --- | --- | --- |
| A | 删除全局禁用逻辑，恢复标准 `test` 任务 | 行为最清晰 | 可能暴露大量历史测试失败，需要集中治理 |
| B | 增加显式开关，如 `-PskipTests=true` 时才禁用 | 保留临时跳过能力 | 需要更新 CI/发布说明 |
| C | 新增独立门禁任务，如 `compileTestJava`、核心单测先恢复，集成测试分阶段 | 风险可控 | 短期存在两套门禁口径 |

**建议**

已采用 B+C：默认不禁用测试；如确实需要跳过，由显式属性控制。保留 `verifyTestCompilation` 用于快速确认测试源码可编译。新增测试任务超时，确保挂起测试会使构建失败。

### P0-2 `isValid()` 与激活流程语义冲突

**事实证据**

- 激活前置条件位于 `DatabaseClusterImpl:1033-1037`。
- `SimpleStateManager:100-102`、`SQLStateManager:584-586`、`BerkeleyDBStateManager:376-378`、`SQLiteStateManager:111-113` 均用活跃库集合判断有效性。
- 分布式实现 `DistributedStateManager:414-419` 则按成员 IP 判断数据库是否对应在线成员。

**影响**

- 手动激活一个非活跃数据库时，普通状态管理器可能直接返回 false，不进入同步流程。
- `isValid()` 在不同实现中语义不一致：有的表示“已活跃”，有的表示“成员存在”。
- 故障检测中也调用 `stateManager.isValid(database)`，容易把“状态有效性”和“数据库是否应保持活跃”混淆。

**候选方案**

| 方案 | 内容 | 优点 | 风险/成本 |
| --- | --- | --- | --- |
| A | 将 `StateManager.isValid()` 明确定义为“数据库节点是否属于当前运行拓扑/成员集合”，本地状态管理器默认返回 true | 语义贴合激活前置判断 | 需要回归故障检测逻辑 |
| B | 新增 `canActivate(Database)` / `isMemberValid(Database)`，保留 `isValid()` 旧语义 | 兼容性更稳 | 接口扩展影响所有实现 |
| C | 在 `activate(D, SynchronizationStrategy)` 中仅对分布式状态管理器做成员有效性判断 | 修改范围较小 | 类型判断会固化实现细节 |

**建议**

已采用 A 的短期修复：本地状态管理器默认认为配置内数据库拓扑有效，分布式状态管理器继续检查成员有效性。长期仍可讨论 B，将“是否活跃”“是否可激活”“是否属于有效成员”拆成不同接口或方法，避免继续复用模糊的 `isValid()`。

### P0-3 库代码直接退出 JVM

**事实证据**

- `ClusterHealthImpl.start()` 捕获 `StartFailException` 后执行 `System.exit(1)`。
- codec 工具类的 `main` 方法也使用 `System.exit(1)`，但这些属于命令行工具入口，风险低于运行时健康检测。

**影响**

- HA-JDBC2 作为库被应用服务器、业务进程或测试容器加载时，健康检测失败会终止整个宿主 JVM。
- 上层无法捕获异常、降级、告警或回滚。
- 多集群同 JVM 部署时，一个集群启动失败会影响其他集群。

**候选方案**

| 方案 | 内容 | 优点 | 风险/成本 |
| --- | --- | --- | --- |
| A | 将 `System.exit(1)` 改为抛出受检/运行时异常，由 `DatabaseClusterImpl.start()` 向上返回失败 | 符合库边界 | 需要确认调用方对启动异常的处理 |
| B | 将节点状态置为 `offline` 并停止健康调度，不终止 JVM | 容错性强 | 上层可能误以为集群已正常启动 |
| C | 通过配置保留历史退出行为，默认不退出 | 兼容旧部署 | 增加配置和文档成本 |

**建议**

默认采用 A，必要时用兼容开关支持旧行为。文档中明确：库代码不得主动退出 JVM。

### P1-1 启动无限等待

**事实证据**

- `checkLocalDb()` 在 `localDbId == null` 时每秒循环，直到本机 IP 命中某个数据库配置。
- `recoverDatabase(true)` 在没有活跃数据库时循环等待 `stateManager.getActiveDatabases()` 非空。

**影响**

- IP 配置错误、容器网络变化、数据库均不可用或状态管理异常时，`start()` 可能永久阻塞。
- 阻塞发生在同步启动路径，应用启动会卡住且难以超时控制。

**候选方案**

| 方案 | 内容 |
| --- | --- |
| A | 为本地数据库识别和初始活跃库恢复增加启动超时配置 |
| B | 启动失败时抛出异常，交给上层重试或熔断 |
| C | 允许无本地数据库启动，但集群状态为未就绪，健康任务异步恢复 |

**建议**

已先修复非分布式启动恢复路径：无分布式健康管理器时会尝试激活所有可连接数据库，避免普通本地集群只激活单个本地 IP 命中的数据库。同时已为本地库发现和活动库等待增加可配置超时，分别由 `ha-jdbc.startup.local-database-timeout-millis`、`ha-jdbc.startup.active-database-timeout-millis` 控制，默认 60 秒；重试间隔由 `ha-jdbc.startup.retry-interval-millis` 控制，默认 1 秒。

### P1-2 主备逻辑混用数据库 ID 与 IP

**事实证据**

- `ClusterHealthImpl.host()` 对远端 host 使用 `databaseCluster.getDatabase(getIp(host))`。
- `DatabaseClusterImpl.getDatabase(String id)` 从 `configuration.getDatabaseMap()` 以数据库 ID 查找；另有专门的 `getDatabaseByIp(String ip)`。

**影响**

- 如果数据库 ID 与 IP 不一致，远端主节点处理可能抛出 `IllegalArgumentException`。
- 这会影响主备状态推进和活跃数据库集合同步。

**候选方案**

| 方案 | 内容 |
| --- | --- |
| A | 改用 `getDatabaseByIp(getIp(host))` |
| B | 明确约束数据库 ID 必须等于 IP，并在 XML 配置加载时校验 |
| C | 引入 `DatabaseNodeIdentity`，统一 member IP、database id、local flag 的映射 |

**建议**

已采用 A：远端 host 处理改用 `getDatabaseByIp(getIp(host))`，并补充测试覆盖“数据库 ID 不等于 IP”的分布式场景。若生产确实依赖 ID=IP，后续仍可追加显式校验和文档化。

### P1-3 分布式文件同步数据完整性与路径边界

**事实证据**

- 下载侧 `SyncMgrImpl.download()` 直接向目标文件偏移写入，没有开始前截断。
- 上传侧 `UploadCommand` 使用 `path + ".tmp"`，`UploadedCommand` 校验后 `Files.move(..., Paths.get(path), REPLACE_EXISTING)`。
- `DownloadCommand` 直接读取传入 path 指向的文件。

**影响**

- 远端文件变小或本地已有旧文件时，下载后可能残留旧尾部。
- 如果 path 来源不受信任，远程命令可能读写任意路径。
- 上传失败时 `.tmp` 文件生命周期和清理策略不明确。

**候选方案**

| 方案 | 内容 |
| --- | --- |
| A | 下载开始时写入临时文件，校验总长度和 MD5 后原子替换目标文件 |
| B | 至少在 offset=0 时 `setLength(0)`，完成后 `setLength(remoteLength)` |
| C | 所有同步 path 先规范化并限制在允许目录白名单内 |
| D | 给 SyncCommand 增加文件大小、摘要、相对路径、任务 ID，避免裸 path 传输 |

**建议**

已完成短期修复：下载改为先写目标同目录临时文件，块 MD5 校验全部通过且长度达到远端长度后再替换目标文件；失败时保留旧文件并清理临时文件。同步命令路径统一规范化为绝对路径，临时文件固定为目标文件同目录兄弟文件；如设置 `ha-jdbc.sync.allowed-roots`，上传、上传完成和下载命令都会拒绝白名单目录外的路径。长期仍可继续采用 D，引入同步任务 ID、远端全文件摘要和相对路径协议，进一步减少裸 path 传输。

### P1-4 许可证元数据不一致

**事实证据**

- `LICENSE` 是 GNU LGPL v3。
- `README.md` License 指向 GNU LGPL。
- 原 `build.gradle` 发布 POM 中写的是 Apache License 2.0。
- 已确认决策：该 fork 继续保持 LGPL 口径，不按 Apache 2.0 重授权处理。
- 当前处理：发布 POM license 已统一为 `GNU Lesser General Public License, Version 3.0`。

**影响**

- 发布到 Maven 仓库的元数据与源码许可证冲突。
- 下游依赖方的合规扫描可能得到错误结论。

**候选方案**

| 方案 | 内容 |
| --- | --- |
| A | 将 POM license 改为 LGPL v3 |
| B | 如果项目实际已改为 Apache 2.0，需同步替换 LICENSE、README、源码头和历史说明 |

**建议**

除非项目明确完成过授权迁移，否则按 A 修正发布元数据。

### P2-1 Linux 系统路径硬编码

**事实证据**

- `/etc/aio/.mfs_ip`
- `/proc/mounts`
- `/etc/ha-jdbc/trace/*`
- `/etc/ha-jdbc/max_unobservable`
- `/proc/net/tcp`

**影响**

- Windows、macOS、容器最小镜像或非标准 Linux 环境下行为不可预期。
- 测试难以隔离真实系统文件。
- 部署差异被隐藏在代码里，配置说明不足。

**候选方案**

| 方案 | 内容 |
| --- | --- |
| A | 抽出 `RuntimeEnvironment` / `HealthPathConfig`，由系统属性或 XML 配置注入 |
| B | 保留默认路径，但允许覆盖，并在不可用时降级 |
| C | 对 Linux-only 能力加显式开关和启动校验 |

**建议**

采用 A+B。默认兼容现有部署，但测试和新环境可注入临时目录。

### P2-2 `TimeoutUtil` 忽略传入 `TimeUnit`

**事实证据**

- `TimeoutUtil.call(Callable, V, long, TimeUnit)` 最终调用 `call(task, timeout, TimeUnit.MILLISECONDS)`，没有使用参数 `unit`。

**影响**

- 调用方传入秒、分钟等单位时会被误当毫秒处理。
- 超时行为与 API 语义不一致。

**候选方案**

- 直接使用传入的 `unit`。
- 增加单元测试覆盖毫秒、秒两种单位。

**处理结果**

已采用上述方案：`TimeoutUtil.call(Callable, V, long, TimeUnit)` 现在会把调用方传入的 `unit` 继续传递到最终 `Future#get(timeout, unit)`；新增 `TimeoutUtilTest` 覆盖秒级超时不被误当毫秒，以及超时后任务会被取消并收到中断。

### P2-3 健康检测资源关闭不完整

**事实证据**

- `ClusterHealthImpl` 创建 `scheduledService` 和 `executorService`。
- `stop()` 只关闭 `scheduledService`。
- host 路径会持续向 `executorService` 提交 `updateNewToken()`。

**影响**

- 集群停止后仍可能保留线程。
- 频繁启动/停止集群时资源泄漏。

**候选方案**

- `stop()` 同时关闭 `executorService`。
- 对 `TimeoutUtil`、`TokenStore` 等静态线程池类补充生命周期管理或复用全局守护线程。
- 增加线程泄漏测试。

### P2-4 异常日志与诊断不统一

**事实证据**

- 多处生产代码使用 `printStackTrace()`。
- 项目同时使用自定义 `net.sf.hajdbc.logging.Logger` 和 slf4j。
- 多个 catch 块吞掉异常或仅写空 message。

**影响**

- 生产日志无法统一采集字段。
- 故障根因可能丢失。
- 运维定位分布式问题时缺少结构化上下文。

**候选方案**

- 统一生产代码使用项目日志抽象或 slf4j，禁止 `printStackTrace()`。
- 为分布式命令日志补充 clusterId、local member、target member、databaseId、command type。
- 保留工具类 `main` 的 stderr 输出，但与运行时代码隔离。

### P3-1 空 SPI 文件

**事实证据**

- `src/main/resources/META-INF/services/net.sf.hajdbc.state.health.observer.ObserveAdapter` 文件长度为 0。

**影响**

- 如果空文件是占位，容易误导扩展点已内置实现。
- ServiceLoader 通常会忽略空文件，但打包后仍暴露一个“有服务文件但没有服务”的信号。

**候选方案**

- 若无内置实现，删除空文件或在文档说明扩展点由用户提供。
- 若应有默认实现，将实现类写入服务文件，并补充加载测试。

## 接口设计讨论

### 状态有效性拆分

建议把当前 `StateManager.isValid(Database<?>)` 拆成更明确的语义：

| 方法 | 语义 | 适用场景 |
| --- | --- | --- |
| `isActive(databaseId)` | 数据库当前是否在活跃集合中 | 查询、展示、故障检测 |
| `canActivate(Database)` | 数据库是否允许进入激活流程 | 手动/自动激活 |
| `isMemberValid(Database)` | 分布式成员是否在线且与数据库匹配 | 分布式激活和故障检测 |

短期也可保留 `isValid()`，但文档中必须明确它不等价于 `activeDatabases.contains(id)`。

### 文件同步命令

建议把当前裸 path 命令升级为：

| 字段 | 说明 |
| --- | --- |
| `syncId` | 一次同步任务 ID |
| `relativePath` | 相对允许根目录的路径 |
| `offset` | 当前块偏移 |
| `size` | 当前块长度 |
| `totalLength` | 文件总长度 |
| `blockDigest` | 当前块摘要 |
| `fileDigest` | 全文件摘要 |

远端执行前统一进行路径规范化：`root.resolve(relativePath).normalize()`，并校验结果仍位于 root 内。

## 回滚策略

| 修复项 | 回滚方式 |
| --- | --- |
| 恢复测试任务 | 保留临时 `-PskipTests=true` 开关，出现历史测试大面积失败时先只启用 `compileTestJava` |
| `isValid()` 语义拆分 | 保留旧方法一版，新增方法逐步迁移调用点 |
| 移除 `System.exit` | 增加兼容开关，例如 `ha-jdbc.health.exitOnStartFail=true`，默认 false |
| 文件同步改为临时文件 | 保留旧协议版本字段，混部期间按能力协商 |
| 路径配置化 | 默认值保持现有 `/etc` 和 `/proc` 路径，不破坏旧部署 |

## 兼容性

- 源码目标为 JDK 8，候选修复不要使用 JDK 9+ API。
- 分布式命令涉及序列化，新增字段要注意 `serialVersionUID` 和混合版本兼容。
- XML 配置新增属性时应保留默认值，确保旧配置可加载。
- 若修改 POM license，属于发布元数据变更，不影响运行时二进制兼容。

## 测试方案

| 测试层级 | 建议用例 |
| --- | --- |
| 构建门禁 | `compileJava`, `compileTestJava`, `test --tests ...` |
| 状态管理 | 非活跃数据库可进入激活流程；活跃集合变更后状态正确 |
| 启动超时 | 本地 IP 不匹配、数据库不可连接、无活跃库时能按配置超时失败 |
| 健康检测 | host/backup/ready/offline 状态流转和 `System.exit` 移除后的错误传播 |
| 文件同步 | 大文件、小文件覆盖大旧文件、MD5 错误、路径越界、断点失败 |
| 分布式 | 成员加入补齐数据库配置、成员离开 durability 恢复、ID 与 IP 不一致 |
| 发布元数据 | POM license 与仓库 LICENSE/README 一致 |

## 分阶段实施计划

### 阶段 1：门禁与合规止血

- 恢复可控测试任务。
- 修正 POM license。
- 文档化当前已知限制。

验收：`compileJava`、`compileTestJava`、一组核心单测可运行。

### 阶段 2：运行时高风险修复

- 移除 `ClusterHealthImpl` 的运行时 `System.exit`。（已完成）
- 为启动等待增加超时和明确错误。（已完成）
- 修复 `isValid()` 语义冲突。（已完成本地状态管理器短期修复）

验收：手动激活非活跃数据库的单元/集成测试通过；启动失败能被上层捕获。

### 阶段 3：分布式与文件同步治理

- 修复 host IP/ID 查找问题。（已完成）
- 文件同步改为临时文件替换，并保留块摘要校验。（短期已完成；最终全文件摘要待协议扩展）
- 增加路径白名单。（已完成；同步任务 ID 待协议扩展）

验收：双节点 smoke 和文件变小覆盖测试通过。

### 阶段 4：环境适配与可观测性

- 系统路径配置化。
- 修复 `TimeoutUtil` 的 `TimeUnit` 传递问题。（已完成）
- 清理 `printStackTrace()` 和吞异常。
- 完善 health/command 日志字段。

验收：非标准目录测试、线程泄漏测试、日志断言通过。

## 开放问题

- 生产配置中数据库 ID 是否始终等于数据库 IP？当前实现已不再要求 host 分支 ID=IP；若它是历史约定，仍可补充配置校验和文档化。
- `StateManager.isValid()` 长期是否应拆分为 `isActive`、`canActivate`、`isMemberValid` 等更明确接口？
- 健康检测中 `/etc/aio/.mfs_ip` 和 `/proc/mounts` 是通用 HA-JDBC 能力，还是某个产品部署形态的定制逻辑？
- 文件同步命令的 path 是否只由可信方生成？当前可通过 `ha-jdbc.sync.allowed-roots` 增加运行时白名单；长期仍建议改为相对路径加任务 ID 协议。
- 测试任务被禁用是否因为历史测试不可维护？如果是，第一阶段需要先确定最小可恢复测试集合。
