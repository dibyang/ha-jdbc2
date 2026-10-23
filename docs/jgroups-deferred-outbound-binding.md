# JGroups 出站延迟绑定

## 配置与兼容性

当前依赖为 JGroups `3.6.20.Final`，使用其原生 `TCP_NIO2.defer_client_bind_addr`，没有修改连接实现。

两个内置 TCP 通道共享 JVM 系统属性：

```text
-Dha.jdbc.jgroups.defer_client_bind_addr=true
```

默认 `false`；未设置或显式设置 `false` 均保留原先提前 `SocketChannel.bind()` 的行为。关闭回退：移除参数或设为 `false`，然后按正常生命周期重新创建通道（通常重启服务）。参数必须传给运行 HA-JDBC 的 JVM；在通道已经初始化后修改系统属性或 XML 不会热更新已有 `NioServer`。

延迟绑定启用时，由 OS 在 `connect()` 选择出站源地址及临时端口。监听地址、监听端口和发现地址不变，但**监听地址不等于强制出站源地址**；多网卡部署必须验证路由选择和对端可达性。

如果有效 `client_bind_addr` 非 null（包括显式通配地址）或 `client_bind_port != 0`，同时启用延迟绑定，工厂在 connect/start 前抛出含通道名称的 `IllegalArgumentException` 并关闭刚创建的通道。不得静默忽略固定源绑定；需要固定源地址/端口的部署应保持延迟绑定关闭。检查的是 JGroups 已解析的有效值，包含其原生系统属性覆盖。

## 实际加载链

- `DistributedLockManager` 使用 `<cluster-id>.lock` 创建 dispatcher。
- `DistributedStateManager` 使用 `<cluster-id>.state` 创建 dispatcher。
- `JGroupsCommandDispatcherFactory` 在 stack=`tcp.xml` 时分别选择 `lock_tcp.xml`、`state_tcp.xml`，交由 `ConfiguratorFactory.getStackConfigurator` 加载，再创建 `JChannel`。
- 两个资源位于 `src/main/resources`，随 JAR 打包；监听默认分别为 7800 和 7801。沿用 JGroups 的文件/URL/classpath 查找规则；若部署覆盖同名 XML，需在**实际加载的 XML** 增加相同占位符。不要认为更新内置资源必然覆盖外置配置。
- 工厂对有效 TCP_NIO2 配置统一校验并在初始化记录通道、配置监听地址/端口/范围、延迟开关及出站地址/端口；成功 connect 后记录实际 physical address。没有逐消息日志。
- dispatcher 保留五次连接尝试；最终启动失败（包括 Error）关闭通道并原样抛出根因，关闭异常作为 suppressed。成功诊断位于连接重试之外；若诊断抛异常，也按启动失败关闭并向调用方报告，不会吞掉异常或重新发起连接。
- 通用 `tcp.xml`（无 lock/state 后缀用途）与 UDP 资源保持不变；自定义 TCP_NIO2 栈可以显式设置原生开关，仍受同一冲突校验保护。

## 验证边界

2026-10-23 本地结果：Windows、Oracle JDK `1.8.0_431`、Gradle `6.9.4`、JGroups `3.6.20.Final`。聚焦测试 10/10 通过；`clean test check jar --offline --no-daemon` 通过，587 项中 585 通过、0 失败、2 项既有 XML 配置测试跳过。真实套接字测试包含两个栈各三种开关组合，关闭后检查 selector 已关闭、acceptor 已退出。最终 JAR 中两个 XML 均含默认 false 的占位符。新增启动回归使用模拟通道验证五次最终失败关闭、原始异常身份、关闭异常 suppressed/防自抑制、Error 立即关闭、末次成功不关闭，以及诊断异常不重试、不吞掉且关闭。

实际收益证据是依赖原始 `NioConnection.connect()` 在 true 时 bind 调用为 0、false/回退时为 1；没有测得或宣称现场端口压力下降比例。连接异常测试使用可记录调用的测试 SocketChannel，不等同于真实网络黑洞超时测试。本机没有 Docker，也没有安装 WSL Linux 发行版；未执行隔离 Linux 压力实验、真实多网卡验证或现场验证。

新增测试覆盖两个打包资源的默认/关闭/开启/回退解析、固定源冲突、依赖真实 `NioConnection.connect()` 的 bind 分支及连接异常关闭；真实 loopback 通道覆盖双端开启、混合开关、双端关闭的入组、双向消息和正常 disconnect/reconnect。测试使用临时端口，不改现场 7800/7801。

Windows loopback 验证不能替代以下候选上线前门禁：

1. 隔离 Linux VM/网络 namespace：记录内核/JDK/JGroups 版本和 socket 状态、端口范围、占用方式；对同一目标集合分别比较开关关闭/开启。分别区分“bind 占用两元组”和“已建立连接占用四元组”，不能把前一种模拟当作现场 FIN-WAIT-1 复现。
2. 真正多网卡：记录连接实际源 IP、目的地址/端口、监听地址及对端接收结果；验证策略路由、防火墙和回程。
3. 网络黑洞/超时、非正常断线及反复失败后，观察连接数、文件描述符和线程回落。这里没有修改 JGroups 的超时或资源回收实现。

不得在已恢复现场缩小临时端口范围、制造耗尽或为了测试修改配置/重启。此开关只消除不必要的提前 bind，有利于 OS 利用四元组分配端口；**不能保证所有耗尽场景成功，也不修复 FIN-WAIT-1 异常不回收**。
