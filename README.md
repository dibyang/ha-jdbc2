# HA-JDBC2: High-Availability JDBC

<https://github.com/dibyang/ha-jdbc2>

## Documentation

- [设计文档](docs/design.md)
- [当前问题扫描与解决方案讨论稿](docs/problem-analysis-and-solution-discussion.md)
- [变更记录](CHANGELOG.md)
- [发布说明](src/main/resources/release-note.md)

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
