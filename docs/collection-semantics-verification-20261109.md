# 收集内容受限语义：本机验证记录（2026-11-09）

> 本页保留初次接入验证时的历史快照：当时新开关默认关闭，文中的“未提交、未推送”及585项结果按当时状态记录。当前默认行为见 [功能说明](collection-semantics.md)。本轮未执行的数据库和真实模型测试，不能用后续独立验收成绩补记为本轮通过。

本轮完成少量固定表达规则、受限内容分类和服务端原文应用的接入。验证范围为当前工作区的对话单元测试与本机 HTTP 模型协议替身；没有连接数据库、Docker 或真实模型，也没有更新任何运行实例、提交或推送。

## 当前执行结果

| 范围 | 执行数 | 通过 | 失败／错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| CollectionDialogueRouterTest | 33 | 33 | 0 | 0 |
| CollectionIntentInterpreterTest | 70 | 70 | 0 | 0 |
| CollectionSemanticServiceTest | 31 | 31 | 0 | 0 |
| 既有对话、解释器、预算、证据与编辑等单元回归 | 451 | 451 | 0 | 0 |
| 合计 | 585 | 585 | 0 | 0 |

最终 Maven 结果为 `BUILD SUCCESS`。首次新增路由／服务测试运行的 59 项中有 1 项测试断言错误：既有模型请求采用 `tool_choice=required` 加仅暴露 `searchKnowledge` 的方式，测试误以为 `tool_choice` 本身包含工具名。已改为严格检查单一工具及 `required`，最终完整选择集全部通过；未为通过测试修改工具权限或放松校验。

最终命令（PowerShell）：

```powershell
$assistantTests = (rg --files backend/src/test/java/local/printer/assistant -g '*Test.java' |
    Where-Object { $_ -notmatch 'Phase5ApiTest.java$' } |
    ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) }) -join ','
mvn -B -ntp -f backend/pom.xml "-Dtest=$assistantTests" test
```

本机原始日志保留于被 Git 忽略的 `.local-validation/collection-semantics-unit-20261109.log`，JUnit XML 位于 `backend/target/surefire-reports/`。`Phase5ApiTest` 和所有数据库／真实服务集成测试未选入本次命令，不计为执行后的跳过项。

## 本次证据证明的行为

- 固定寒暄、导航和明确控制表达继续直接处理；非固定输入才请求分类。
- 自由文本、带标签多字段和通过原有明确授权门禁的首次准备前缀只产生候选；分类前不填入字段。
- 替身返回 OUT_OF_SCOPE 时，“我喜欢吃汉堡”“母猪能上树”不填故障；普通闲聊只返回范围引导，没有第二次模型调用。
- 替身返回 FIELD_VALUE 时，只应用当前已保存的 USER 原文；设备屏幕引用不由“汉堡”等黑名单判断。
- 多字段候选整体应用或整体拒绝；无效电话／邮箱格式不能靠标签或 FIELD_VALUE 放行。
- 普通无收集语境不能凭 FIELD_VALUE 建立授权；取消、暂停、期限、目标变化、最新消息、版本、CAS 和未知写入结果继续受保护。
- 售后问题进入原有必需检索契约，并拒绝在该知识分支准备草稿；正常字段齐全仍只准备草稿，不创建工单。
- 独立分类模型名称正确转发，普通知识回答保留原模型；分类无工具，严格 JSON，一次尝试、零重试，预算或服务失败后不回退填值。
- 两开关均关闭时，需内容判断的候选不直接应用；仅开启旧 extra 时保留其合格旧入口，新模式开启时优先且失败不回退旧模式。

## 限制

协议替身按测试预设返回分类，本记录不证明真实模型能正确识别上述表达，也没有选定或验证轻量模型的质量、延迟或费用。未配置独立模型名时仍复用 `AI_CHAT_MODEL`。

新开关默认关闭，源码测试通过不表示既有实例已更新或已开启。首次准备仍保留原有明确授权门禁，复杂引用可以先单独准备报修再补充；普通表单、已有草稿编辑和历史已存内容不在本次语义检查范围。旧 extra 冻结评测集保持原样，其历史成绩不作为本功能质量证据。

OpenSpec 严格校验和 Git 差异空白检查通过；Git 仅提示现有 LF／CRLF 转换，没有空白错误。
