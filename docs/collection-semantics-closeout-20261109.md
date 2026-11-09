# 收集内容受限语义：本轮收尾记录（2026-11-09）

> 本页保留首错停止时的历史快照：当时新开关默认关闭，文中的“未提交、未推送”、失败、未运行项和源码指纹均按当时状态记录。当前默认行为见 [功能说明](collection-semantics.md)，后续独立验收见 [最终验收记录](collection-semantics-acceptance-20261109.md)；后续通过不改写本轮失败，也不表示本轮未执行的测试曾经通过。

**验收结论：FAIL_INCOMPLETE，完整验收未通过。** 本轮边界修订及本机回归完成；真实重评在C24首次分类不匹配后停止。HTTP／数据库集成的执行条件未满足，故未启动数据库、未执行这8项集成测试。

## 本轮最小修订

- 删除提示词中笼统的“表达混合”，明确多个标签字段并存本身不属于事实与提问混合。
- 对部分候选有效、任一候选明确用途无关的输入，整条归OUT_OF_SCOPE；独立现场事实与提问、猜想或提交意愿不明确仍归UNCLEAR。
- 模型仍只输出分类，服务端授权、原文、状态版本、期限、原子应用与明确确认建单门禁保持既有实现。
- 新增6项参数化单元／协议执行用例；另新增8项 `CollectionSemanticsIT`，已编译，尚未执行。
- 原24题、gold与执行顺序不变；旧轮失败记录、manifest和锁文件保留，新轮独立冻结和记录。

## 实际执行结果

| 范围 | 已执行 | PASS | FAIL／ERROR | SKIPPED／未运行 |
|---|---:|---:|---:|---|
| 相关单元与本机协议替身 | 591 | 591 | 0／0 | 0 |
| 评测器预检场景 | 8 | 8个场景符合预期 | 0个预检异常 | 0 |
| 真实模型分类 | 5 | 4 | 1／0 | 18条跳过 |
| 固定集本地联系方式门禁 | 1 | 1 | 0／0 | 0 |
| 新增HTTP／数据库集成 | 0 | — | — | 8项未运行，未产生JUnit跳过记录 |

591项包含 `CollectionDialogueRouterTest` 33、`CollectionIntentInterpreterTest` 72、`CollectionSemanticServiceTest` 35，以及既有20个相关测试类451项。未选择数据库测试与 `Phase5ApiTest`，不把未选择项写为本轮JUnit SKIP。

评测器预检为success、HTTP 503、非法JSON、分类不匹配、预算、凭证回显、重定向与断连8个场景；错误场景按预期停止，不能把这些预设输出当作真实模型质量成绩。

真实重评完成于 **2026-11-09 14:39:34 UTC+08:00**，逐题如下：

| ID | 原文 | 预期 | 实际 | 结果 |
|---|---|---|---|---|
| C20 | 苹果 | UNCLEAR | UNCLEAR | 本地PASS（0调用） |
| C01 | 我喜欢吃汉堡 | OUT_OF_SCOPE | OUT_OF_SCOPE | PASS |
| C02 | 母猪能上树 | OUT_OF_SCOPE | OUT_OF_SCOPE | PASS |
| C05 | 我喜欢吃汉堡 | OUT_OF_SCOPE | OUT_OF_SCOPE | PASS |
| C22 | 故障：我喜欢吃汉堡；联系人：小王 | OUT_OF_SCOPE | OUT_OF_SCOPE | PASS |
| C24 | 我喜欢吃汉堡，请准备报修 | OUT_OF_SCOPE | UNCLEAR | FAIL |

18个未执行项：C06、C10、C15、C16、C17、C19、C21、C23、C03、C04、C07、C11、C12、C18、C14、C09、C08、C13。本轮模型题实际执行部分全部预期OUT_OF_SCOPE；正向填写、知识问题、屏幕引用、联系人等尚未获得本轮真实执行证据。

## 失败含义与保留的限制

C22本轮满足原预期，说明该样例在本次修订后通过；不能据此推断所有多字段语句都已解决。

C24“我喜欢吃汉堡，请准备报修”预期OUT_OF_SCOPE，实际返回合法JSON `{"kind":"UNCLEAR"}`，HTTP 200、正常结束、无工具调用。严格分类仍为FAIL，未修改gold、未将失败改判为通过。

当前生产提示词已说明固定准备结尾只代表流程意愿；实际模型仍给出UNCLEAR，表明“流程意愿与字段用途”的区分尚未满足该样例的严格预期。现有证据不能确定模型内部判定原因。

按 `AssistantConversationService` 当前分支，只有FIELD_VALUE进入字段应用；OUT_OF_SCOPE与UNCLEAR均拒绝应用。本轮5条真实响应的危险FIELD_VALUE提议为0，属于保守分类差异；本轮没有进行实际业务HTTP／数据库写入验证，不能称实际误填率为0，不能称全功能验收通过。

不将两轮成绩拼接。第一轮为3条真实PASS、1条本地PASS、1条FAIL、19条SKIP；本轮为4条真实PASS、1条本地PASS、1条FAIL、18条SKIP。两轮分别完整发送4次和5次，仅可说明合计9次供应商请求，不能组合成完整集准确率。

## 配置、预算及停止

- 模型：DashScope `qwen-plus` 别名，temperature=0、max_tokens=128、严格四枚举JSON；没有做轻量模型选型或不可变快照确认。
- 本轮新增预算最多23次，实际预占5次、确认完整发送5次、收到响应5次；单并发、零重试，首错停止后未补跑。
- 单次body上限8192字节，总上限188416字节；本轮实际发送body合计17948字节。
- 供应商usage：输入3238 tokens、输出34 tokens，缺失记录0。未读取价格或账单，不推算人民币费用。
- `gateway.calls/upstream_requests/real_upstream_requests` 是预占数，发送与响应另以逐题传输标记核对。
- 固定集SHA256：`9aa1ef23bb40bd3c163f154241a6755158edd549164b19c0f437f38370ff353a`。
- 本轮manifest SHA256：`b393a7d210d16f060265efb2d352ea048ca1227ac2121c162350d196a08a9adc`。全部158份当前生产源码隔离重新编译，class集合、class哈希与编译输入在执行前后校验。
- 凭证仅经父进程私有环境和stdin进入固定上游网关；SDK只持有回环占位凭证，日志和证据不持久化真实Authorization头。

## HTTP／数据库阶段的处理

新增 `CollectionSemanticsIT` 8项覆盖合法多字段应用、精确C22整条拒填、澄清零填值、503零重试、联系方式零调用、草稿到明确确认及幂等建单、知识分支只读、归属与CSRF。

已准备复用本机MySQL 8.0.44的隔离运行器，限定全新数据目录、loopback端口、全新 `printer_dialogue_it_` 库与独立用户，不接触日常实例。运行器要求本轮真实评测完整PASS，且生产源码与真实manifest一致，然后才可创建资源。

本次对实际FAIL记录执行负向门禁检查，结果PASS：在数据库初始化、建目录和启动MySQL之前阻断。**实际数据库连接0，数据库初始化0，集成测试执行0。** 这是门禁验证，不是8项HTTP／数据库测试通过。

## 命令与证据

单元命令为选择 `backend/src/test/java/local/printer/assistant/*Test.java` 并排除 `Phase5ApiTest`，执行离线Maven：

```powershell
$assistantTests = (rg --files backend/src/test/java/local/printer/assistant -g '*Test.java' |
    Where-Object { $_ -notmatch 'Phase5ApiTest.java$' } |
    ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) }) -join ','
mvn -o -B -ntp -f backend/pom.xml "-Dtest=$assistantTests" "-Dprinter.build.directory=.local-validation/collection-semantics-closeout/unit-build" test
```

单元原始日志位于 `.local-validation/collection-semantics-closeout/unit.log`，XML位于其 `unit-build/surefire-reports/`；数据库门禁记录为同目录 `integration-gate-check.json`。

可共享的摘要与逐题表：

- [结构化收尾证据](evidence/collection-semantics/closeout-20261109-v2.json)
- [固定24题逐题结果](evidence/collection-semantics/closeout-20261109-v2.csv)

原始真实结果本机目录：`仅开发者本机留存，不随公开源码分发`。目录名success表示执行场景，不表示评测通过，以verified-summary的FAIL_INCOMPLETE为准。C22、C24的去凭证请求与实际响应均保留。

## 最终状态

默认 `ASSISTANT_COLLECTION_SEMANTICS_ENABLED=false` 保持关闭；没有更新日常实例、改普通表单或数据库结构，没有Embedding、重排、邮件或通知外呼，未提交、未推送。

本轮按一次修订、一次真实重评及首错停止的边界结束。剩余已知缺陷为C24严格分类不匹配，另有18条模型题和8项集成测试未执行；本记录不能作为完整验收通过或生产上线依据。
