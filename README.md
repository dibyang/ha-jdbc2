# HA-JDBC2: High-Availability JDBC

<https://github.com/dibyang/ha-jdbc2>

## Documentation

- [设计文档](docs/design.md)
- [当前问题扫描与解决方案讨论稿](docs/problem-analysis-and-solution-discussion.md)
- [变更记录](CHANGELOG.md)
- [发布说明](src/main/resources/release-note.md)

## 3.7.1 发布说明

3.7.1 修复 H2 恢复失败隔离、带历史记录重启、初始化重试和超时收尾，并支持默认关闭的 JGroups 出站延迟绑定。
同一集群须协调升级，保留数据库和持久恢复记录，不能与旧恢复协议混跑。
升级、配置及回退边界见[发布说明](src/main/resources/release-note.md)；
恢复设计和隔离灰度证据见[H2 恢复说明](docs/h2-restore-failure-isolation-design.md)，
延迟绑定配置见[配置说明](docs/jgroups-deferred-outbound-binding.md)。
发布沿用现有 Gradle publishing 任务，本地验证使用 `publishToMavenLocal`。

## Verification

Use `.\gradlew.bat compileJava` for the narrow production compilation check.
Use `.\gradlew.bat verifyTestCompilation` to compile test sources without
running tests.
Use `.\gradlew.bat test` as the default quality gate before and after adding or
changing key behavior. Temporary test skipping must be explicit via
`-PskipTests=true`. The test task times out after 120 seconds by default to make
hung tests visible; override it with `-PtestTimeoutSeconds=<seconds>` when a
specific long-running verification needs more time.

## License

[GNU Lesser General Public License](https://www.gnu.org/licenses/lgpl.html)
