# 受限语义默认开启：配置调整记录（2026-11-09）

> 本页记录默认值调整时的验证快照：新开关改为默认开启，文中的“未提交、未推送”保留当时状态。当前行为见 [功能说明](collection-semantics.md)。本轮只执行单元／本机协议回归及测试编译，未执行数据库或真实模型测试；此前验收成绩仍属于各自的历史运行。

按用户要求，正常使用默认开启收集内容的受限语义理解。此前关闭时的行为是保守澄清，不会恢复未经用途判断的宽松填值；默认开启用于避免正常收集持续停在澄清步骤。

## 改动

- `AssistantConversationService` 的 `ASSISTANT_COLLECTION_SEMANTICS_ENABLED` 默认值从 `false` 改为 `true`；显式 `false` 仍可关闭。
- 相比此前验收快照，生产源码仅修改上述默认值；解释器、模型配置、分类提示词、解析、预算、状态及确认建单门禁没有变化。
- 使用真实 Spring 属性注入验证未配置时进行分类并应用合法原文，以及显式关闭时零分类、零字段应用。测试上下文隔离系统环境和系统属性，不加载数据库自动配置。
- 9个历史工具协议／旧 extra 模式集成测试类显式固定新开关为 `false`，保持各自预设的协议和分支；没有删除或弱化断言。新模式的 `CollectionSemanticsIT` 保留显式 `true`。
- 当前功能与运行文档同步新默认值；此前验收报告、逐题结果和源码指纹保留。

## 本轮实际验证

| 范围 | 结果 |
| --- | --- |
| 23个相关单元／本机协议测试类 | 607 PASS，0 FAIL，0 ERROR，0 SKIP |
| 其中 CollectionSemanticServiceTest | 41 PASS，包含默认开启和显式关闭的属性绑定及行为验证 |
| 旧模式测试配置调整后的 test-compile | 87份测试源码编译通过，未执行数据库或真实模型集成 |
| 生产源码对照此前真实评测 manifest | 仅默认值一处变化，受限分类解释器原样保留 |

本轮命令使用离线 Maven 和独立输出目录：

```powershell
$assistantDefaultTests = (rg --files backend/src/test/java/local/printer/assistant -g '*Test.java' |
    Where-Object { $_ -notmatch 'Phase5ApiTest.java$' } |
    ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) }) -join ','
mvn -o -B -ntp -f backend/pom.xml "-Dtest=$assistantDefaultTests" "-Dprinter.build.directory=.local-validation/collection-semantics-default-on/build" test
mvn -o -B -ntp -f backend/pom.xml "-Dprinter.build.directory=.local-validation/collection-semantics-default-on/build" test-compile
```

原始日志位于 `.local-validation/collection-semantics-default-on/unit.log` 和 `test-compile.log`，JUnit XML 位于其中的 `build/surefire-reports/`。未选择的数据库和真实服务测试不计为JUnit SKIP。

## 生效与边界

新包未配置该开关时采用默认开启；已有实例绑定旧JAR或显式设置 `false` 不会自动改变。本轮未构建发布包或升级、启动日常实例。

默认开启仍需要有效AI配置，并共享已有调用、字节和超时预算。离线运行器的聊天地址仍为本机协议服务，聊天返回503且不转发，故只能验证失败澄清；真正 `AI_ENABLED=false` 时不请求分类器。真实模式下实际使用分类会产生供应商用量。

此前23条真实分类加1条本地门禁、8项隔离HTTP／MySQL的结果见 [原验收快照](collection-semantics-acceptance-20261109.md)。本轮没有重新调用真实模型、访问数据库或启动Docker，不能将历史成绩称为当前配置调整后的同次真实联调。

未提交、未推送。默认开关已按用户最新要求调整，分类失败或显式关闭时仍澄清，建单继续要求页面明确确认。

旧专用集成测试仍待迁移。显式关闭新模式不会恢复旧版的字面填槽行为；相关旧测试仍需按各自验证目标调整收集前置流程与模型协议预设，之后才能实际执行并核对原断言。本轮 `test-compile` 只证明这些测试可以编译，不证明旧集成断言已经通过。
