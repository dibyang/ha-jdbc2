# HA-JDBC2: High-Availability JDBC

<https://github.com/dibyang/ha-jdbc2>

## Documentation

- [设计文档](docs/design.md)
- [当前问题扫描与解决方案讨论稿](docs/problem-analysis-and-solution-discussion.md)

## Verification

The legacy Gradle build currently disables tasks whose names contain `test`.
Use `.\gradlew.bat compileJava` for the narrow production compilation check.
Use `.\gradlew.bat verifyTestCompilation` to compile test sources while the
full test gate is being restored.

## License

[GNU Lesser General Public License](https://www.gnu.org/licenses/lgpl.html)
