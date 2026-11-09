# 评测方法、结果与局限

检索与业务历史结果记录于 2026-11-03，旧 extra 语义专项补充于 2026-11-08，收集内容语义专项补充于 2026-11-09。业务回归、真实供应商调用、回答质量和用户采用分别报告。健康检查、协议 fixture 或源码构建通过都不能代替真实模型质量证据。

## 40 问三配置对照

语料包含 716 个片段，40 问分为 DEV/ACC 两组，每组 17 个可答、3 个范围外。相同题目及证据绑定比较纯向量、RRF、RRF 加重排，共 120 行。后续修复已接触两组问题，因此最终结果属于已见回归；原始 gold、分母和未达门槛事实保留。

“必要语义单元覆盖@5”（项目早期命名 Recall@5）先判断前五条片段是否完整覆盖一个必要语义单元的某组允许证据，再按题内覆盖比例和可答问题做宏平均。它不同于标准相关文档 Recall@5。MRR@5 统计前五条中首个相关片段的倒数排名；完整 gold 要求该题全部必要单元被覆盖。“AI 完整任务支持”是 AI 对实际回答与引用的支持性审核，未经过独立人工盲审。

| 集合 | 配置 | 必要语义单元覆盖@5 | MRR@5 | 完整 gold | AI 完整任务支持 |
|---|---|---:|---:|---:|---:|
| DEV | 向量 | 0.6235 | 0.6471 | 10/17 | 9/17 |
| DEV | RRF | 0.6235 | 0.6471 | 10/17 | 9/17 |
| DEV | RRF + 重排 | 0.6559 | 0.7549 | 10/17 | 9/17 |
| ACC | 向量 | 0.6176 | 0.4794 | 8/17 | 8/17 |
| ACC | RRF | 0.7157 | 0.6765 | 11/17 | 10/17 |
| ACC | RRF + 重排 | 0.7745 | 0.7765 | 11/17 | 10/17 |

原约 0.8824 的覆盖门槛没有达到。较高的首条相关性仍可能遗漏必要条件、警告或跨片段步骤；完整 gold 11/17 和完整任务支持 10/17 说明这些问题尚未完全解决。不能把 77.45% 写成回答准确率，也不应与不同语料、题型和标注口径的公开榜单直接比较。

原厂商语料与完整响应不随公开仓库分发，因此本表是历史实测摘要，不能只凭公开样例逐字复现。未测参数不称最优，不为追求分数修改原 gold 或删掉失败题。

## 可分发的 8 问

自编的 `validation/product-portfolio-holdout/frozen-v1/` 包含 CC0 文档、题目、gold 和冻结哈希。它首次执行后已暴露，之后只能称公开回归集。

- 首次执行：6 个可答问题中 5 个充分受支持，严格结构契约 6/8。
- 修复后的已见回归：可答 6/6 充分；H07 返回 NO_EVIDENCE；H08 因缺少实际设备计数返回合理的 NEEDS_INPUT，但原 gold 为 NO_EVIDENCE，因此严格契约仍为 7/8，执行器返回非零。
- 上述充分性为 AI 审核，没有独立人工盲审，不能称生产准确率或独立未见测试全部通过。

验证冻结文件不会调用模型：

```sh
python validation/product-portfolio-holdout/run.py verify --package validation/product-portfolio-holdout/frozen-v1
```

真实执行前创建 real 实例，使用 `status` 输出的完整 JAR SHA-256，按 [执行器说明](../validation/product-portfolio-holdout/README.md) 发布和执行。会产生供应商用量；执行器不自动重试，也不自动改变 gold。每次执行保留独立本机原始记录。公开包不包含开发者本机账号、历史完整响应或私有资料。

## 等待 extra 的受限语义分类（2026-11-08）

该专项评估收集现场信息时的 `FACT / QUESTION / UNCLEAR` 分类，不测 RAG 检索或整段答案质量。固定集为自编、合成、公开的已见回归：36 题，三类各 12 题；另有 16 条门禁对照，不发送真实模型。功能源码见公开提交 [`476fbf3`](https://github.com/dashstar22/printer-after-sales-assistant/commit/476fbf305c7654b6838b6e35a3ff8f5ebe9738c0)。

两轮分别调用 DashScope `qwen-plus` 36 次，单并发、零重试；temperature=0、max_tokens=128、严格 JSON Schema，单次完整请求体最多 4,096 字节、每轮总计 147,456 字节。协议、服务或预算失败即停。模型为别名，不可变快照记为 UNKNOWN。

| 真实分类批次 | 有效协议 | 严格匹配 | 危险 FACT 提议 | 受控失败 / 跳过 |
|---|---:|---:|---:|---:|
| v1 首轮 | 36/36 | 26/36 | 4/24 | 0 / 0 |
| v2 修复后的已见回归 | 36/36 | 34/36 | 0/24 | 0 / 0 |

严格匹配以全部 36 题为分母。危险 FACT 指 gold 为 QUESTION 或 UNCLEAR、模型却返回有效 FACT，以该两类的 24 题为分母；这是提议风险，不是数据库实际误填率。v1 原始失败和冻结包保留，修复后另建 v2，没有覆盖原结果或改题。v2 达到本固定集至少 33/36 且危险 FACT 为 0/24 的预设门槛。

v2 仍有 S25、S27 两题预期 UNCLEAR、实际 QUESTION，均为事实夹提问的表达。现有服务对两类都走不填值的澄清路径，因此未产生危险 FACT；严格分类偏差仍保留。v2 另冻结 12 条补充对照，未调用真实模型，也不并入 36 题分母。

随后另用 6 次真实模型调用完成六个预定合成场景的 HTTP/MySQL 联调，业务结果 6 PASS、0 FAIL、0 SKIP。C06 同样保留 UNCLEAR→QUESTION 的严格分类偏差；业务通过依据是状态保持和澄清行为。该联调与上述两轮分类是三个独立授权批次；详见 [专项验证范围](verification.md#受限现场语义专项2026-11-08)。

0/24 只表示本固定集未观察到危险提议，6 个业务场景通过也不证明未见语句准确率、线上零误填或生产验收。样本未经独立人工盲审；本节旧模式的 `ASSISTANT_OBSERVATION_SEMANTICS_ENABLED` 默认仍为 `false`，当前新模式及其独立证据见下节。

公开资产与离线入口：

- [v1 固定题及原始规划](../validation/observation-semantics/README.md)、[共享评测适配器](../validation/observation-semantics/adapter/README.md)及 [E3 默认关闭的受控执行入口](../validation/observation-semantics/adapter/E3_README.md)。
- [v2 修复回归包](../validation/observation-semantics-v2/README.md)包含 2026-11-08 对应版本的源码指纹和 v1 历史快照；协议替身默认只访问本机回环地址，不使用数据库或真实模型。冻结 manifest 和原资产保留，不随新增收集功能改写。

```sh
python -X utf8 validation/observation-semantics-v2/verify.py --historical-v1-only
```

上述命令只核对冻结 v1 资产与已保存的旧源码，不声明其与当前生产源码一致。当前新增收集功能已改变 `DialogueRouter` 等被指纹覆盖的源码，直接运行旧 v1 或完整 v2 verifier 会报告历史身份差异；这不应通过重写 manifest 消除。完整 v2 校验、单元与本机替身应在公开历史提交 [`476fbf3`](https://github.com/dashstar22/printer-after-sales-assistant/commit/476fbf305c7654b6838b6e35a3ff8f5ebe9738c0) 的独立检出目录，按该版本说明执行，需要 Java 17、Maven 已缓存依赖及对应应用 JAR。真实逐题 raw 和六场景私有运行器不分发，公开资产不等于能够直接重放开发者本机的全部真实联调。

## 收集内容的受限语义分类（2026-11-09）

新 `CollectionIntentInterpreter` 评估 `FIELD_VALUE / KNOWLEDGE_QUESTION / OUT_OF_SCOPE / UNCLEAR` 四分类，覆盖自由文本、标签字段、明确准备请求携带内容及普通自然语言分流。它与上述仅等待 extra 的三分类属于不同功能和题集，不能互换分数或合并分母。

已有 [最终验收快照](collection-semantics-acceptance-20261109.md) 对应当时默认关闭的源码：606项单元／本机协议测试通过；固定24题包含23条真实 `qwen-plus` 分类和1条本地联系方式门禁，全部通过；另有8项隔离 HTTP/MySQL 集成通过，集成模型为本机协议替身。真实分类与数据库业务验证属于不同运行，不能称为真实模型与数据库的同次端到端验证。

23条真实分类中，9条应填值均匹配 gold；14条不应填值中危险 `FIELD_VALUE` 提议为0。该24题在开发修复中已见，v1、v2及v3的原始失败记录和gold保留，最终v4从原题集开头完整重评，没有拼接多轮分数。两轮v3＋v4实际供应商调用45次；详细分类、用量及逐题结果见验收快照和 [结构化证据](evidence/collection-semantics/acceptance-20261109-v4.json)。这不证明未见输入准确率、实际线上误填率或生产并发。

随后将 `ASSISTANT_COLLECTION_SEMANTICS_ENABLED` 默认值调整为 `true`。[默认开启记录](collection-semantics-default-on-20261109.md) 的已有单元／本机协议结果为607 PASS、0 FAIL、0 ERROR、0 SKIP，包含默认开启与显式关闭的实际 Spring 属性绑定验证；该次没有重新调用真实模型、连接数据库或更新日常实例。此前606项、23＋1及8项结果仍绑定原验收快照，不改记为默认开启后的真实重测。

关闭新开关不会恢复旧的字面填槽路径。`LayeredDialogueIT`、`AssistantRepairIT`、`AssistantTicketIT` 等历史专用集成测试仍有依赖旧收集前置流程的场景，尚需迁移并在隔离数据库中实跑；仅固定开关或编译通过不足以确认它们当前通过。本次文档同步未执行这些数据库测试，不填报未经执行的失败或通过数字。当前行为与配置见 [功能说明](collection-semantics.md)，验证范围见 [验证记录](verification.md#收集内容受限语义专项2026-11-09)。

## 业务与工程验证

本节保留 2026-11-03 指定版本的历史记录，未因新增专项重记为当前完整回归。

最终实现的 quick 回归为 380 项：344 PASS、0 FAIL、36 专用环境 SKIP。36 项分别属于 Phase5ApiTest 7、StoreDatabaseTest 6、Phase5CandidateCacheTest 11、ExternalCallBudgetTest 10、RedisProtectionTest 2。它们有独立集成通过证据：Phase5ApiTest 的 7 项按最终源码实跑，其他 29 项复用本次较早未变模块的验证，不声称全部用最终包重跑。完整隔离中间件合计 52 次执行通过，其中含继承场景重复执行，不能当作 52 个新增能力。

最终源码还完成真实 HTTP/MySQL/Redis 的 Phase5 7/7（模型与检索用本地协议 fixture），最终 JAR 在新空库完成真实 DashScope 初始化链：CC0 样例发布 7 个片段、有据与无依据问答、显式确认、幂等建单、客服关闭、客户查回、重启后引用和时间线保留。桌面 1440 与手机 390 宽度检查通过。这次最终包演示的邮件由 Mailpit 本机捕获，通知关闭，没有重新执行外部投递。

此前指定候选版本已在 2026-11-02 完成真实外部渠道专项验收：QQ SMTP 邮件 7 次发送、7 次本人实收及链接操作；飞书群超时提醒和日报各 1 条，均有本人实收确认。另有指定版本的 19 项隔离部署、故障、加密备份与空目标恢复检查通过，包含 10 项真实 MySQL 预算测试的结果。它们保留原日期、包版本和分母，不与最终 quick 累加，也不称为最终包上的新增执行。详细范围及脱敏回执见 [验证记录](verification.md)。

QQ 的旧账号迁移使用全新合成旧数据和分开的副本，不能据此声称真实客户历史库迁移成功；飞书批次已停止且写凭证已撤销，不代表持续调度运行。备份恢复在 Windows Docker Desktop 的 Linux 容器中演练，尚未验证独立 Linux 主机、异地容灾或公网生产部署。真实目标用户试用和独立人工答案盲审仍未验证，开发者本人确认邮件/群消息实收不等于这两项验证。

源码回归：`python -X utf8 scripts/regression.py quick`；独立中间件：`python -X utf8 scripts/regression.py middleware`。前者不需要中间件，后者新建隔离容器并在结束后停止、保留数据；两者均无外部模型调用。历史 opt-in `*IT` 中部分依赖未公开厂商语料，不属于这些可复现入口。

公开源码的 quick 运行 Java 测试和 5 项运行器安全契约。依赖受限语料的历史 Python 评测门禁不随包分发，结果单独标记 NOT_DISTRIBUTED_PRIVATE_EVALUATION，不计作通过。原始开发工作区保留此门禁及其历史证据。
