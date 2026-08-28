# 变更记录

本文件记录 HA-JDBC2 的主要版本变化。发布说明同时保存在
[`src/main/resources/release-note.md`](src/main/resources/release-note.md)。

## [3.7.0] - 2026-08-28

### 新增

- 增加 GitLab JDK 8 构建基线、测试超时及完整测试结果汇总。
- 增加 `Version.properties`、`BuildInfo.properties`、JAR manifest、Maven POM 和 Java 8 class major 的构建身份一致性校验。
- 健康检查运行期文件路径支持显式配置。

### 修复

- 没有实际数据库调用任务时，通过既有异常工厂抛出“无活跃数据库”，避免空任务继续进入 executor。
- 同步上下文构造失败时关闭已取得的连接和自有 executor，并保留首个异常及清理异常。
- 修复启动与同步等待边界、`TimeoutUtil` 时间单位传递和健康检查停止时的内部线程池收口。
- MySQL 外部进程诊断对密码参数脱敏，不再把明文凭据写入日志或异常消息。
- 修复健康检查按 IP 解析数据库和网卡解析缓存问题。

### 性能

- 非锁定 SQL 在文本快速判断后不再读取 JDBC metadata。
- 负载均衡数据库的 local-first 顺序改为写入时构造、读取时复用的不可变快照。
- `Tracer.isTrace()` 将文件存在性检查限制为每个 tracer 最多每 3 秒一次，不增加后台线程。
- `SimpleDatabaseMetaDataCache` 缓存锁定 SQL 分类所需的只读 metadata 核心；显式 flush 和数据库成员变化仍会使其失效。
- 网卡健康检查在一次刷新中批量枚举接口和地址，不再按配置 IP 逐项调用 native 查询。
- 1000 节点固定负载 A/B 中，CPU 中位数下降约 12.6%，JFR 总估算分配下降约 11.5%，四个目标热点栈归零。

### 依赖

- H2DB 更新到 2.3.1。

### 兼容性

- 运行时和产物继续以 Java 8 为最低版本，所有 class major 必须为 52。
- `ResultsCollector.collectResults` 增加泛型 `throws E`。二进制 JVM 描述符不变；第三方源码直接调用或实现该接口时，重新编译可能需要处理该异常类型。
- Tracer 开关文件的启停可见性从即时文件查询调整为最多 3 秒延迟。
- metadata 缓存不保存 JDBC `Connection`；显式 flush、成员激活或停用后首次访问会重新构造快照。

### 已知限制

- `XMLDatabaseClusterConfigurationFactoryTest` 中两个历史测试仍被 `@Ignore`。
- Maven Central 的实际上传、Portal 校验和发布不由构建测试自动完成，发布者需在签名本地发布检查后手动执行。

[3.7.0]: https://github.com/dibyang/ha-jdbc2/compare/v3.6.70...v3.7.0
