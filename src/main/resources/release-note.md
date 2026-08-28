## v3.7.0

1. 在 Java 8 基线上补齐测试、构建身份和发布元数据校验，最终构件可追溯到完整 Git 提交。
2. 修复无活跃数据库调用、同步上下文构造失败清理、启动与同步边界、超时单位传递及健康检查停止时的资源收口问题。
3. MySQL 外部进程诊断不再输出明文密码参数，运行期健康检查路径可显式配置。
4. H2DB 依赖升级到 2.3.1；非锁定 SQL 不再触发 metadata 查询，锁定 SQL 复用可失效的只读 metadata 核心缓存。
5. 负载均衡数据库顺序使用不可变快照，Tracer 文件状态按 3 秒 TTL 检查，网卡地址按刷新周期批量枚举，减少高频排序、文件系统、metadata 和 native 网卡查询。
6. `ResultsCollector.collectResults` 增加泛型 `throws E`。JVM 方法描述符保持不变；直接调用或实现该接口的第三方源码在重新编译时可能需要处理该异常类型。

### 验证

- Oracle JDK 8u431 全量 `clean test check jar`：566 tests，564 passed，0 failed，2 skipped。
- 非 SNAPSHOT `publishToMavenLocal` 生成主 JAR、sources、javadoc、module、POM 及五个 `.asc`，GPG 验签全部通过。
- Java 8 临时消费者从 Maven Local 解析 `net.xdob.ha-jdbc2:ha-jdbc2:3.7.0`，并确认 `Version.CURRENT=3.7.0`。
- 1000 节点固定负载 A/B：CPU 中位数下降约 12.6%，JFR 总估算分配下降约 11.5%；四个目标热点栈归零。
- Store 分组快照修复后的 60 分钟整体长稳：监控请求无 failed、rejected、timedOut、cancelled、late 或 pending 残留。

### 已知限制

- `XMLDatabaseClusterConfigurationFactoryTest` 中两个历史配置测试仍被 `@Ignore`，本版本未扩大该测试缺口。
- Tracer 文件状态变化最迟 3 秒生效。

## v3.2.0
1. add H2 support.

## v3.2.1
1. fix bug for more network interface down.
2. maxElectTime from 5 minute to 4 minute.

## v3.2.2
1. fix bug for more instances.

## v3.2.3
1. fix bug for cluster merge.

## v3.2.4
1. optimize cluster merge speed.

## v3.2.6
1. add joda time lib.

## v3.2.7
1. fix bug for one node network down.

## v3.2.8
1. more data sync support.

## v3.2.9
1. min node count elect  support.
