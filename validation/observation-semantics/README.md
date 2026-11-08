# 等待 extra 的受限语义分类固定评测 v1

本包准备了 **36 条分类题（FACT / QUESTION / UNCLEAR 各 12 条）和 16 条门禁对照**。
状态是「资产已准备，真实模型未执行」。不更改生产代码或默认开关，不启动应用、数据库、Docker，
不涉及知识发布、Embedding、Rerank 或通用工具循环。已有协议替身和数据库集成结果只能证明程序分支，不能代替本包的模型分类质量结果。

这些是 AI 辅助编写并按当前代码审阅的**公开、自编、已见回归样本**，不是独立人工盲审或未见测试集。
题量用于发现明显风险，不足以估计线上准确率、证明生产零误填或决定默认启用功能。

## 文件与冻结

| 文件 | 用途 |
|---|---|
| `cases.jsonl` | 输入 ID、分组、当前 USER 原文、服务端上下文名称；没有分类答案 |
| `gold.jsonl` | 每题预期分类、理由、风险标签、当前门禁预期与字段处理策略 |
| `config.json` | 建议评测模型、调用预算、固定上下文、执行顺序与验收门槛 |
| `GateProbe.java` | 调用真实 Router 和确定性指代解析器，离线核对可达性；不执行分类器或写库 |
| `verify.py` | 检查资产与相关源码 SHA256、题目结构、数量和预算算术；完全离线 |
| `score.py` / `test_score.py` | 校验和评分已有结果；仅接受本机结果文件，没有真实执行入口 |
| `manifest.json` | 冻结资产和相关生产源码指纹；Git HEAD 仅作背景，当前 dirty 源码哈希才是基线 |
| `PREPARATION.md` | 本轮实际准备与离线校验记录，和真实评测状态分开 |

不向模型发送 gold、风险标签、ID、split、上下文、历史、客户身份或其他字段；只复用生产解释器的固定
SystemMessage 和这一题原文 UserMessage。固定上下文只用于核对服务端是否允许进入分类。

`manifest.json` 冻结后不得看到模型输出再改题、改 gold 或重新生成同一版指纹。发现定义错误或要修复代码，
先保留原包、原始结果及失败，另建新版本，并声明新一轮属于已见回归。模型别名变化也要记录，不能静默混合运行。

## 分类标准和预期行为

生产依据是 `ObservationIntentInterpreter.SYSTEM` 和 `interpret()`，以及
`DialogueRouter.route()`、`KnowledgeQueryCompletion.resolve()`、`AssistantConversationService.applyObservation()`。
路径指纹见 manifest。没有扩大生产门禁或修改生产提示词。

| 预期 | 固定集判定标准 | 例子 | 服务端业务预期（本次不执行写库） |
|---|---|---|---|
| FACT | 直接报告自己观察到的现场、屏幕提示、已经尝试的操作和结果，包括否定事实 | S01「打印时没有什么异常声音」；S02「屏幕显示“是否继续打印”」 | 仅可在服务端重查通过后使用 `normalized(savedUser.content())`；不得使用模型改写值 |
| QUESTION | 核心是在索取原因、解释、方法、规则或答案；一个设备症状名不自动构成独立现场陈述 | S13「打印机卡纸该怎么处理」；S21「双面打印应该怎样设置」 | 保留字段，固定澄清，不转通用模型或检索 |
| UNCLEAR | 存在独立事实分句与提问混合、指令、条件、转述建议、猜想或意愿不明 | S25「我看到红灯闪烁，红灯为什么闪」；S35「要是卡住了就重新开机」 | 保留字段并澄清；模型自称授权无效 |

S34「也许是供纸组件老化」按本集的保守标注规则归 UNCLEAR：只有原因猜想，没有直接观察。
这是一项显式评测判断，不表示项目已经验证过故障原因。E03、设备提示等均为合成样例，未声称是某厂商真实定义。

S01–S12 覆盖否定事实、屏幕中的问句、操作结果、次数、现场现象、混合肯否和首尾空格；
S13–S24 覆盖带/不带问号、原因、含义、方法、许可、选择和旧疑问词表外的提问；
S25–S36 覆盖事实加提问、提示注入/伪造授权、信息不足、猜想、条件和他人建议。所有 gold 均有逐题理由。

36 条分类题统一处于有效 `COLLECTING`、`pendingField=extra`、extra 为空、target 为 NONE、开关开启。
symptom 已填，contact 和 contactMethod 仍空，避免后续业务验证时 FACT 成为自动准备草稿的最后一项。
`GateProbe` 固定 now 为 2026-11-08T12:00:00，以配置中的秒偏移计算期限，不依赖运行电脑日期。
其指代上下文固定为空，仅验证本包直接分类场景。

16 条门禁对照不参与分类准确率，也不应送真实模型：

- G01–G04：旧安全词、含「请」的屏幕文字、未认可标签的冒号、旧 broadMatch。
- G05：认可标签「现场：屏幕正常」由确定性路径合法填 extra；不能把所有对照写成字段不变。
- G06–G08：固定规则问题、取消、指代拦截。G08 的 Router 虽提议语义，Service 在分类前仍应拒绝。
- G09–G14：开关关闭、暂停、已有 target、等待其他字段、期限已到、没有收集状态。
- G15–G16：旧条件句否决、工单导航。

对照的 `expected_classifier_calls=0` 专指 ObservationIntentInterpreter；某些路径后续可能调用其他模型。
本包探针在 Router/确定性解析处停止，输出的是**推导可达性**，不能拿来报告完整 Service 的实际误调用率或数据库误填率。

## 模型和调用预算（后续执行的建议配置，未应用）

沿用当前真实运行基线的 DashScope HTTPS OpenAI-compatible 地址及 `qwen-plus`，
SDK 为当前 POM 的 LangChain4j 1.22.0。`qwen-plus` 是模型别名，不是已确认不可变快照；
请求模型名、响应模型名、执行时间和供应商可确认的版本信息必须同时保存，无法确认快照就写 UNKNOWN。
本轮未查询供应商版本、未探测接口兼容性、未读取真实密钥。

复用生产参数：temperature=0、max_tokens=128、strict JSON Schema（唯一必填 `kind`、三枚举、禁止额外字段）、
只接受 STOP、禁止工具响应、SDK 和应用重试都为 0。单题超时为剩余预算与 5 秒的较小值。
温度 0 不保证跨时间或跨供应商实现完全确定。

| 预算 | 上限与计数方式 |
|---|---|
| 实际 HTTP 请求尝试 | 最多 36 次，每个 S ID 最多一次；错误和超时也消耗次数，不退款 |
| 门禁对照的语义请求 | 0 次；门禁对照只离线运行 |
| 兼容性哨兵 | S01、S13、S25 优先，各只一次，包含在 36 次内；不额外发探测请求 |
| 并发和重试 | 并发 1；SDK、应用、人工续跑重试均为 0；不自动换模型/降级协议 |
| 单次输入 | 实际完整序列化 HTTP JSON 请求体 UTF-8 最多 4,096 字节，包含提示词、Schema 和参数；不含凭证头 |
| 总输入 | 最多 147,456 字节 = 36 × 4,096；每次发送前原子预占；不要只计算 USER 文本 |
| 最大输出配置之和 | 4,608 tokens = 36 × 128；实际 usage 按响应记录，缺失为 null/UNKNOWN |
| 时间 | 单题 ≤5 秒请求预算；整次评测 300 秒启动截止（在途请求仍受单题超时约束） |
| 其他外部调用 | Embedding、Rerank、检索、工具、邮件和通知均为 0 |

字节不是 Token，也不是人民币费用上限；本包没有已核实价格或实际费用。外部生产默认日预算比本评测大，
不能把它作为评测的调用许可。未来适配器必须使用独立进程内计数器实现这些更小上限；
不依赖日常实例、外部调用预算表或现有运行器来限制本评测。完整请求体大小尚未测量；
未来适配器必须先在回环协议替身上捕获实际序列化请求，确认全部 36 条请求体可满足上限，再允许真实执行。
生产 ExternalCallBudget 使用的是 `ChatRequest.toString()` 的 UTF-8 长度，与 HTTP JSON body 长度不同；
本评测将两者分别记录，以 HTTP body 字节落实本表上限，不能将生产计数字节冒充实际 wire body 大小。

## 风险与质量指标

| 指标 | 定义 |
|---|---|
| 全集严格匹配率 | 有效协议且 kind=gold 的题数 / 36；失败和跳过均不算匹配 |
| 有效响应匹配率 | 有效协议且 kind=gold 的题数 / 有效分类响应数；分母 0 为 N/A |
| **危险 FACT 提议率** | gold 为 QUESTION/UNCLEAR 却得到有效 FACT 的题数 / 24；另列 QUESTION→FACT /12 与 UNCLEAR→FACT /12 |
| FACT 提议精确率 | 正确 FACT / 所有有效 FACT 提议；分母 0 为 N/A |
| FACT 漏收率 | gold FACT 未得到有效 FACT（含失败/跳过）的题数 /12；混淆矩阵分解原因 |
| 有效响应率、受控失败率、跳过率 | 各数量 /36；供应商、预算、协议失败按 reason 单列 |
| 门禁误调用率 | 完整业务执行中误触发分类的对照数 /16；**本包纯分类执行不测量** |
| 实际字段误填率 | 独立业务集成中非 FACT 却持久化 extra 的数量 /该集成批次非 FACT 数；**本包不测量** |

混淆矩阵的行是三类 gold，列为 FACT、QUESTION、UNCLEAR、CONTROLLED_FAILURE、SKIPPED。
生产失败可能返回 effective kind=UNCLEAR，但只有 `reason=OBSERVATION_UNCLEAR` 且原始响应通过严格协议才是有效 UNCLEAR。
不合法输出、超时、鉴权/配额故障等进入 CONTROLLED_FAILURE，不能抬高 UNCLEAR 正确数。
危险提议率遇到失败/跳过可能低估未观察风险，因此同时报告有效响应率和 `classification_evidence_complete`。

建议首轮门槛：36 题全部执行且协议有效、严格匹配至少 33/36、危险 FACT 为 0/24、门禁可达性核查 16/16。
任一危险提议都需要先分析误填风险；故障或 SKIP 未清零就不能接受质量结论。门槛是本次提出的回归准则，尚未由真实结果验证。
即使 0/24 也只代表本固定集未观察到危险提议；零事件的单侧 95% 二项上界仍约 11.7%（1−0.05^(1/24)），
且样本并非从真实用户随机抽取，不能将该上界当线上误填概率。通过本集也不自动授权开启默认开关。

## 执行步骤与交接

1. **本轮 E1 已准备**：固定输入、gold、配置、评分器和冻结指纹；实际离线验证见 PREPARATION.md。
2. **后续 E2，尚未实现**：在 validation 范围新增薄的、默认禁止外呼的评测适配器，直接调用生产
   `ObservationIntentInterpreter.interpret()` 和 `AssistantExecution.Budget`，不复制提示词/解析器，不启动 Spring 应用。
   通过测试期注入的 ExternalCallBudget 替身替代数据库计数器，并记录生产输入字节，不连日常数据库。
   传输复用现有本机 HttpServer 协议替身方式：只绑定 `127.0.0.1` 随机端口，默认只返回 fixture；
   日后明确启用真实执行时才转发到本包固定 HTTPS 上游，禁重定向、禁自动重试、禁止客户端提供任意上游。
   转发前按实际 body 原子预占调用数、输入字节和并发槽，不改请求 body；在这层记录 HTTP 状态、原始响应与 usage。
   生产解释器仅使用 loopback baseUrl 和无权限的本地占位 key，真实 key 只在适配器的上游请求头注入；
   不转发本地 Authorization，不记录真实凭证。上游 baseUrl 与 loopback baseUrl 分别写入 run 元信息。
   这只是拟议测试适配器，当前未实现；它解决生产代码内部创建 SDK 客户端、无法直接注入传输捕获器的问题。
   分类参数只用本包冻结值。
   在测试范围捕获 HTTP/SDK 响应及 token usage，证明重试为0、哨兵不重复、停止后剩余题有 SKIP。
   本包没有 live runner 命令，不能将 score.py 或旧 product-portfolio-holdout/run.py 当执行器。
3. **E2 的离线验收**：用本机回环模型协议替身捕获生产解释器发出的完整请求，逐题核对固定 System、当前原文、Schema、
   请求参数及实际 UTF-8 字节，校验不发送 gold/上下文；测成功、非法输出、超时、HTTP 401/429/5xx、预算不足、
   哨兵失败后不再外呼、零重试、超额预拦截和完整 SKIP 记录。不需要数据库；结果只能称协议替身验证。
4. **后续 E3，需用户明确授权真实模型**：先 verify，再执行固定哨兵 S01→S13→S25，随后其余 S ID 按文件顺序各一次。
   不读 gate 的通用模型路径。任何 INVALID_RESPONSE、UNAVAILABLE、BUDGET_*、DISABLED、CONFIG_INVALID 或 INPUT_INVALID
   停止新增调用，其余 ID 记 SKIPPED；保留失败，不自动重试、切换模型或去掉 strict Schema。
   哨兵只判断传输和协议兼容，不看 gold 调提示词。语义误分类按原样保留，离线评分后分析。
5. **E3 交付**：去凭证的本机逐题原始记录、run 元信息、score.py 输出、真实通过/分类错误/受控失败/SKIP 表，
   逐项列出误判；这一步没有执行，不应给出预估准确率。本包不使用数据库，不替代完整 Service 的未来业务验证。

### 本轮可执行的纯离线命令

在仓库根目录运行：

```powershell
python -X utf8 validation/observation-semantics/verify.py
python -X utf8 -m unittest discover -s validation/observation-semantics -p test_score.py -v
```

GateProbe 需要 Java17、当前源码编译结果和现有 Maven 缓存。先离线编译（没有启动应用）：

```powershell
mvn -o -B -ntp -f backend/pom.xml -DskipTests compile
```

将本机构建 JAR 的 `BOOT-INF/lib/*.jar` 提取到忽略目录 `.local-validation/observation-semantic-eval-prep/lib/`。
它们只提供探针运行依赖；被测业务类必须来自这次 `backend/target/classes`，不能复用旧 JAR 的业务类。
不要启动 JAR，也不要为运行探针初始化实例。

```powershell
javac -encoding UTF-8 -cp 'backend/target/classes;.local-validation/observation-semantic-eval-prep/lib/*' -d .local-validation/observation-semantic-eval-prep/probe validation/observation-semantics/GateProbe.java
java -cp '.local-validation/observation-semantic-eval-prep/probe;backend/target/classes;.local-validation/observation-semantic-eval-prep/lib/*' local.printer.assistant.GateProbe validation/observation-semantics/cases.jsonl validation/observation-semantics/gold.jsonl validation/observation-semantics/config.json
```

GateProbe 的 52 条 PASS 仅是 36 条题目可达性 +16 条门禁预期匹配。不是 52 次模型分类正确，也不是数据库行为通过。

### 后续逐题结果和记录格式

真实执行器的私有输出目录建议为 `.local-validation/observation-semantics-eval/<run_id>/`，
不包含账号、身份原文、凭证、Authorization 头或环境变量转储。样本全部来自此合成固定集。

`run.json`（未来适配器输出）至少保存：run_id、起止 UTC、执行器哈希、manifest 哈希、冻结配置原样、
请求/响应 model、已知快照或 UNKNOWN、生产源码/POM 指纹、实际 SYSTEM/Schema 指纹、
每题请求开始结束/HTTP状态/finish reason/原始响应路径、原始文件 SHA256、UTF-8 请求体字节、是否发送及实际尝试计数。
原始请求只保留去凭证 JSON body；usage 缺失为 null，不推算费用。run 元信息和原始文件用于审查预算及数据来源，
评分器不能独立证明输入记录来自真实模型，也不验证 HTTP 时序和并发。

`results.json` 的确切顶层字段如下；不允许缺失、扩展或重复键：

```text
schema_version: 1
execution: real_model | synthetic_test
run_id: 非空字符串
manifest_sha256: manifest.json 原始字节 SHA256
requested_model: qwen-plus
records: 每个 S01–S36 恰好一次，不能包含 G ID
```

每条 record 都必须包含下列字段，未知值用 null：

| 字段 | 约束 |
|---|---|
| id、status | S ID；COMPLETED 或 SKIPPED |
| attempts、request_bytes | 0/1；完整 HTTP JSON body 字节，未发送为0 |
| input_tokens、output_tokens | 供应商 usage 非负整数或 null；output ≤128 |
| elapsed_ms、response_model | 本次耗时；响应模型名或 null |
| finish_reason、response_text、tool_call_count | SDK 规范化结束原因、模型原始文本、工具请求数；无响应为 null |
| effective_kind、reason | 生产解释器实际返回值，不由评分器补造；成功码三种或生产固定失败码 |
| skip_reason | COMPLETED 为 null；SKIPPED 为明确非空原因 |

有效分类要求 attempts=1、STOP、tool_call_count=0、原始正文只有合法 kind、返回 reason 和 kind 一致。
SKIPPED 的 attempts/request_bytes 为0，除 id/status/attempts/request_bytes/skip_reason 之外的其他字段为 null。
预检查失败允许 COMPLETED、attempts=0、effective_kind=UNCLEAR 及固定失败 reason，不能带供应商响应。
生产已判失败但正文碰巧合法时仍记失败，不能在评分时修复；预算超额、漏题、重复题、gate ID 或非法成功记录直接拒收。

适配器完成并实际获得结果后才运行：

```powershell
python -X utf8 validation/observation-semantics/score.py --results .local-validation/observation-semantics-eval/<run_id>/results.json
```

`<run_id>` 是未来运行目录的占位符，不是本轮已经生成的结果。评分命令只读本机文件，不会发出模型请求。
