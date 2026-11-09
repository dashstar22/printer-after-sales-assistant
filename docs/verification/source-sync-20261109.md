# 源码与文档同步验证（2026-11-09）

本记录对应收集内容受限语义默认开启后的源码发布检查。当前功能见 [收集内容受限语义](../collection-semantics.md)，早期模型与数据库验收仍保留各自的日期、版本、失败与分母。本轮没有调用真实模型、连接数据库、启动 Docker 或运行任何演示实例，也没有发送邮件或通知。

## 本轮实际执行

| 范围 | 执行与结果 | 说明 |
|---|---|---|
| 开发仓库相关对话单元／本机协议测试 | 607 PASS，0 FAIL/ERROR，0 SKIP | 选择 assistant 下的 `*Test.java`，排除 `Phase5ApiTest` |
| 公开源码目录完整 Maven package | 782 项：746 PASS，0 FAIL/ERROR，36 SKIP；BUILD SUCCESS | 使用离线 Maven 与独立构建目录，完成所有测试源码编译和 Spring Boot JAR 打包 |
| 运行器安全契约 | 5 PASS，0 FAIL/ERROR/SKIP | `python -X utf8 -m unittest discover -s scripts -p test_portfolio_runtime.py -v`，未创建实例或容器 |
| 公开8问冻结资产校验 | PASS，8题，0外部调用 | `python -X utf8 validation/product-portfolio-holdout/run.py verify --package validation/product-portfolio-holdout/frozen-v1`；只验证冻结资产，不是重新生成答案或取得8/8答案成绩 |
| 旧语义历史资产校验 | PASS，9个资产、14份旧源码快照 | `python -X utf8 validation/observation-semantics-v2/verify.py --historical-v1-only`；不声明旧源码等同当前实现 |

607项是完整打包选择集的一部分，两次执行不相加作为独立测试能力数量。完整打包跳过项为 `Phase5ApiTest` 7、`StoreDatabaseTest` 6、`Phase5CandidateCacheTest` 11、`ExternalCallBudgetTest` 10、`RedisProtectionTest` 2；本轮没有提供这些36项的专用环境。

历史专用 `*IT` 未选入本轮默认打包，不能混入36个JUnit跳过项。`LayeredDialogueIT`、`AssistantRepairIT`、`AssistantTicketIT` 等仍需迁移依赖旧字面填槽的前置流程，并在隔离数据库中实跑；把新开关固定为 `false` 不会恢复旧填值行为。新增 `CollectionSemanticsIT` 的8项通过属于此前默认关闭的验收快照，本轮未重跑。

## 文档与公开边界

README、架构、运行、评测及验证说明同步了新模式默认开启、四分类、服务端原文应用、失败澄清、旧模式兼容和实例固定JAR的边界。公开的旧语义冻结manifest及原始资产保持不变；完整v2生产源码指纹已与本次新实现不同，复现该历史版本应使用 [476fbf3](https://github.com/dashstar22/printer-after-sales-assistant/commit/476fbf305c7654b6838b6e35a3ff8f5ebe9738c0) 的独立检出目录，不能重写manifest消除版本差异。

公开提供新功能说明、四份历史记录与两轮结构化摘要／逐题CSV，保留失败与原有默认值、未提交等历史状态。公开JSON副本删除了主机路径、进程标识及本机命令，增加原始私有JSON的SHA-256以说明来源；原始证据未被覆盖。私有运行器、模型原始请求响应、凭证、日志、数据库和学习进度继续保留本地。

当前开发仓库的旧Windows启动入口归档到 `scripts/legacy-launchers/`，静态路径及PowerShell语法检查通过，没有实际启动旧环境。公开源码继续提供四个现行portfolio入口，没有加入依赖旧本机私有状态的历史启动器。

本轮结果证明所选本机测试、打包和资产完整性；不构成真实模型质量重测、数据库业务重验、部署升级、外部渠道实收或生产验收。
