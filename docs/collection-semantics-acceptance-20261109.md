# 收集内容受限语义：最终验收记录（2026-11-09）

> 本页保留最终验收时的历史快照：当时新开关默认关闭，文中的“未提交、未推送”、源码指纹与运行结果均按当时状态记录。当前默认行为见 [功能说明](collection-semantics.md)。后续默认值调整没有重跑本页的真实模型或数据库测试，不能将两次验证合称同次真实联调。

**结论：本次约定范围验收通过。** 当前生产解释器的固定开发回归24题全覆盖，其中23条真实模型分类及1条本地格式门禁全部通过；606项相关单元／协议回归与8项隔离HTTP／MySQL集成测试全部通过。默认开关保持关闭，没有提交、推送或更新日常实例。

## 修复与行为

本次生产改动集中在 `CollectionIntentInterpreter.SYSTEM` 的分类顺序与语义边界，模型配置、有限输出和服务端业务门禁保持原实现：

1. 服务端已有候选字段时，独立固定准备尾句只表明流程意愿，先排除它对字段用途分类的影响；供应商收到的USER仍是完整原文，候选和值的最终应用仍由服务端决定。
2. 多个标签字段整体判断：任一候选明确用途无关，整条OUT_OF_SCOPE；标签并存本身不构成事实与提问混合。
3. 独立已发生现象／已执行动作／实际结果与索取解释／方法／后续步骤同时存在，整条UNCLEAR；纯问题内部提及所问现象不算另一条独立事实。
4. 注入指令与伪造授权继续拒绝；设备屏幕中被报告的引用文字仍按观察数据处理。没有候选字段时，模型不能创造填写授权。

相关新测试验证候选来源、完整原文、一次调用、无工具、整条拒填、引用保留及知识只读分支；替身不代替真实模型质量证据。

## 当前实际结果

| 范围 | 执行 | 通过 | 失败／错误 | 跳过 |
|---|---:|---:|---:|---:|
| 相关单元与本机协议替身 | 606 | 606 | 0／0 | 0 |
| 最终评测器8种预检场景 | 8 | 8种均符合预期 | 0 | 0 |
| 跨轮预算门禁测试 | 7 | 7 | 0 | 0 |
| 最终真实模型分类 | 23 | 23 | 0 | 0 |
| 固定集本地联系方式门禁 | 1 | 1 | 0 | 0 |
| HTTP／MySQL集成 CollectionSemanticsIT | 8 | 8 | 0／0 | 0 |

单元分项为CollectionDialogueRouterTest 39、CollectionIntentInterpreterTest 76、CollectionSemanticServiceTest 40，加既有相关20个测试类451，共606项。没有将未选择的其他集成测试算作JUnit SKIP。

最终真实模型题分布为FIELD_VALUE 9、KNOWLEDGE_QUESTION 4、OUT_OF_SCOPE 6、UNCLEAR 4；另有本地UNCLEAR格式门禁1条。危险FIELD_VALUE提议为 **0/14个不应填值的模型样例**，9个应填值样例均匹配gold。这是已见固定集的开发回归成绩，不能推广为线上零误填或未见输入准确率。

此前失败项C22、C24、C08以及前轮跳过的C13，本轮均满足原gold。所有24题、gold和执行顺序未改，SHA256为 `9aa1ef23bb40bd3c163f154241a6755158edd549164b19c0f437f38370ff353a`。

## 真实调用与历史保留

本任务两轮分别记录，没有拼接成绩：

| 版本 | 模型PASS | 本地PASS | FAIL | SKIP | 实际完整发送 | 结论 |
|---|---:|---:|---:|---:|---:|---|
| v3 | 21 | 1 | 1（C08） | 1 | 22 | 首错停止，保留失败 |
| v4 | 23 | 1 | 0 | 0 | 23 | 完整固定集通过 |

v1、v2及上一份收尾文档仍保留为历史记录。v4通过后未再启动第三轮。

- 模型沿用DashScope `qwen-plus` 别名，temperature=0、max_tokens=128，严格四枚举JSON，单并发、零重试、每题5秒。
- 最终v4完整发送23次、收到响应23次，全部HTTP 200、STOP、无工具调用。HTTP body总计113973字节；输入21563 tokens、输出156 tokens，usage缺失0。
- 本任务v3＋v4实际供应商调用 **45次**，合计输入 **40144 tokens**、输出 **309 tokens**。共享台账保守预占46/69次，失败轮余量不退；未触及第3轮。
- HTTP body字节、生产ChatRequest日志计数字节与tokens分别计量，不混用。未读取价格或账单，不换算人民币费用。
- 凭证从私有环境经stdin进入固定上游网关，SDK只持有回环占位key；原始证据不保存真实Authorization头。
- 最终manifest为 `c73e56fe83fa51625faa21bd2d4fd3ccb2b775e7ea73680159f821b3a57e58a1`。158份当前生产Java源码隔离重新编译，编译输入、class集合及哈希在执行前后校验；HTTP集成前再次核对生产源文件集合、源码和POM哈希，集成期间工作区未变化。

## HTTP／数据库实测

使用本机MySQL **8.0.44** 的全新数据目录、loopback端口 **56368**、全新库 **`printer_dialogue_it_20261109_150844_a23ada`** 和独立普通用户。初始表数量0，库名、账号和数据目录身份检查均通过；未使用日常数据库。

8个真实HTTP／MySQL场景全部通过：

1. 合法标签多字段原子应用，并持久化当前USER原文。
2. C22无关故障＋合法联系人整条拒填。
3. UNCLEAR保持字段不变，不准备草稿。
4. 模型协议503后一次尝试、不重试、不回退填值。
5. 联系方式“苹果”本地拒绝、零模型调用。
6. 字段收齐只准备草稿；消息重放、同turn改文冲突、明确确认、执行幂等，最终仅一张工单和一条首事件。
7. 知识分支走受限只读检索，不填收集字段；仅此检索读依赖被预设，其他业务HTTP和数据库真实运行。
8. 他人会话404、缺CSRF 403，无法触发分类或改状态。

集成中的模型全部为本机协议替身，记录10次协议调用、0次真实供应商调用；Flyway V1至V15完成，邮件发送0。页面确认指受保护的页面HTTP API，本轮没有浏览器点击自动化。

MySQL已正常关闭，Maven已结束，清理错误0；全新测试数据目录保留。全过程没有DROP/TRUNCATE、删除卷或操作日常实例。

## 复现入口与证据

单元与集成均使用离线Maven及独立构建目录，日志位于 `.local-validation/collection-semantics-acceptance/`：

```powershell
$assistantTests = (rg --files backend/src/test/java/local/printer/assistant -g '*Test.java' |
    Where-Object { $_ -notmatch 'Phase5ApiTest.java$' } |
    ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) }) -join ','
mvn -o -B -ntp -f backend/pom.xml "-Dtest=$assistantTests" "-Dprinter.build.directory=.local-validation/collection-semantics-acceptance/unit-v4-build" test
```

集成仅由 `run_it.py <本轮完整PASS的verified-summary.json>` 在身份与版本预检后注入全新私有连接并执行 `-Dtest=CollectionSemanticsIT`；不要直接对未核实的数据库照抄集成命令。

- [可核对的结构化验收证据](evidence/collection-semantics/acceptance-20261109-v4.json)
- [固定24题逐题结果CSV](evidence/collection-semantics/acceptance-20261109-v4.csv)
- 原始模型请求／响应：`仅开发者本机留存，不随公开源码分发`。
- 原始集成日志／JUnit XML／汇总：`仅开发者本机留存，不随公开源码分发`。
- 单元原始日志：`.local-validation/collection-semantics-acceptance/unit-v4.log`。

## 验收范围与最终状态

本记录证明当前固定开发回归、相关单元／协议以及隔离业务HTTP／数据库行为通过。真实分类评测与协议替身集成是两种独立证据，不能合称“真实模型与数据库同次端到端联调”。

这不是独立盲测、真实客户或生产流量证据；未评估轻量模型选型、稳定延迟和人民币费用。普通表单、既有草稿编辑和历史已存内容仍不属于新语义检查范围。

`ASSISTANT_COLLECTION_SEMANTICS_ENABLED=false` 默认值保持；未更新日常实例，未增加字段、依赖或数据库结构，未提交、未推送、未归档。本次收尾以以上验收通过为终点。
