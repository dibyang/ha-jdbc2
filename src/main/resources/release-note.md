## v3.7.1（2026-11-13）

### 修复与功能

1. H2 破坏性恢复失败后持久隔离目标库，失败不再报告为同步完成；受隔离库不得激活、反向同步或成为主节点。故障解除后通过新操作自动恢复、自动上线，不要求人工放行。
2. epoch、操作 ID、输入摘要及成员范围共同约束恢复；超时、重启和迟到成功不能绕过隔离。实际 SQL 未退出时保持写排斥，不重复清库；故障前备份持续保留。
3. 启动阶段等待真实可用路由，防止 JPA 收到空数据库集合；已识别本机配置读取避开启动监视器。隔离期间保留已确认远端主机引用，避免恢复后循环同步。
4. 仅恢复执行连接使用 `NETWORK_TIMEOUT=0`，等待物理 SQL 结束后正常关闭，避免 H2 客户端读超时后遗留独占 TCP 会话。业务连接、独立验证连接、既有 RPC 和语句超时均不变；这不是 SQL 硬超时保证。
5. 内置锁通道和状态通道支持可选 JGroups 出站延迟绑定，默认关闭。通道启动最终失败、Error 或诊断失败时关闭资源并保留根因；自动激活失败不再静默。
6. 显式 `PhysicalInvocationScope` 保护已开始的物理调用收尾，未启用作用域的旧路径不变；不引入线程池，不承诺强制终止驱动。
7. 重启时先收口 COMMIT/ABORT，再保留 PENDING 写保护，避免混合历史记录重复申请不可重入全局写锁而阻断状态通道；锁冲突和失败隔离仍按原门禁处理。
8. 已 local/active 的本机数据库初始化重试不重复改配；重复停止不再访问已关闭的恢复记录，物理 SQL 未结束时仍保留所有权并等待最终关闭。

### 升级、配置及回退

- 同一 HA-JDBC 集群全部成员协调升级到支持新恢复协议的版本，不能与旧协议混跑滚动升级；原选主、仲裁配置保持不变，不强制单节点选主。
- 数据库与其持久恢复目录、catalog、初始化标记及记录一起保存和备份。默认使用现有绝对数据库路径相邻目录，也可沿用 `ha-jdbc.h2-recovery-dir`；不能放入随部署清空的目录。
- 未决隔离期间不得删除记录、强行激活或降级到不识别该协议的旧版本。恢复已完成且确认无未决记录后，回退仍须按整组版本及数据库备份方案操作。
- 可选 JVM 参数 `-Dha.jdbc.jgroups.defer_client_bind_addr=true` 同时作用于内置 7800 锁通道和 7801 状态通道。默认 `false`；关闭回退为移除参数或设 `false`，正常重新创建通道后生效，不热更新已有 server。
- 开启延迟绑定时，由 OS 选择出站源地址和端口，不改变监听地址。若配置固定 `client_bind_addr` 或非零 `client_bind_port`，同时开启延迟绑定会拒绝启动；固定源绑定部署应保持关闭。外置同名 XML 须配置相同占位符。详见 `docs/jgroups-deferred-outbound-binding.md`。
- 运行基线仍为 JDK8，H2DB 默认依赖仍为 2.3.1、JGroups 为 3.6.20.Final。本版本不包含临时库切换，也不恢复事故中已经丢失的数据。

### 验证与已知边界

- 启动修复后的 JDK8 全量：738 tests，736 passed，0 failed，2 skipped；现场同版 H2DB 2.3.3 的接收器、隔离及真实双 JVM 回归 33/33 通过。
- 启动修复候选在完整 Manager 2.3.29-3、Linux JDK8/H2DB 2.3.3 双节点隔离灰度通过：旧版故障对照、同记录换包启动、导入失败及重启隔离、失败节点不能借选主放行、自动恢复上线、双向接管。两端 921 张用户表、42 条合成记录摘要一致，130 秒内无新增同步或物理恢复。
- 前期候选另有 109/109 Linux 回归及 RPC 超时、迟到 SQL 的完整 Manager 灰度；本轮未重做全部历史故障场景，不将旧候选证据等同最终候选全面验收。
- 本轮正式 3.7.1 的本地构建、发布产物、签名及临时消费者验证结果另存于 `build/reports/release-3.7.1-20261110/`。本地发布不代表远程仓库已上传或生产已部署。
- 两个既有 XML 配置测试仍为 `@Ignore`；隔离灰度不代替真实存储业务全面验收。JGroups 开关的真实多网卡、Linux 端口压力等边界保持原配置文档说明，不宣称根治 FIN-WAIT-1 回收问题。

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
