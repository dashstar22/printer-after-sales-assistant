# E2 本机协议替身评测适配器

本页保留 E2 的 fixture 入口说明；后续新增的 E3 受控入口及本轮测试见 [E3_README.md](E3_README.md)。
`run.py` 仍只运行 E2，本页所述唯一 fixture 模式指该入口；E3 在独立 `run_e3.py` 中默认关闭真实转发。

本目录实现固定集的 E2：复用当前生产 `ObservationIntentInterpreter.interpret()` 和
`AssistantExecution.Budget`，对真实 SDK 发出的 HTTP 请求执行本机协议验证。
唯一运行模式为 `loopback_protocol_fixture_only`，只连接自己创建的 `127.0.0.1` 随机端口，
真实模型调用和数据库访问均为0。本 E2 入口没有服务地址、真实模式或凭证选项。

父目录 v1 的9个冻结资产、manifest 和14个源码指纹保持原样；父 README 中的「尚未实现」属于冻结时记录。
E2 当前实现与执行证据以本目录和每次新运行目录为准，不重新生成 v1 的 gold 或 manifest。

## 实现与记录

| 文件 | 职责 |
|---|---|
| `run.py` | 核对冻结包；离线编译当前源码；提取本地 JAR 的依赖；编译适配器；执行 fixture；调用原评分器 |
| `ObservationEvalAdapter.java` | 直接调用生产解释器；按三个哨兵优先执行36条 S 题；固定失败后停止；输出完整结果及 SKIPPED |
| `LoopbackFixture.java` | 只绑定回环地址；核对完整请求；执行内存调用/字节/并发预算；生成固定协议响应或注入故障 |
| `check_runs.py` | 纯离线核对真实运行记录、逐题 body/哈希、停止位置、尝试次数、SKIP 与各场景预期 |
| `test_run.py` | 入口边界、输出路径、依赖提取、安全环境、冻结失配、失败保留和 Windows 命令查找测试 |

固定 SYSTEM 在测试期从生产类反射读取，只用于核对请求；请求构造、Schema、SDK 客户端和响应解析均由生产解释器完成。
Java 运行路径不读取 gold。fixture 按已接受的请求序号循环返回 FACT、QUESTION、UNCLEAR，与题目含义无关。
所有 results/score 都标为 `synthetic_test`，其中匹配数和风险率仅验证评分链路，不能当模型分类质量。

适配器不启动 Spring 应用，不创建 JDBC 连接。测试注入的 ExternalCallBudget 子类只在进程内计数；
其父类所需的事务管理器是「调用即抛 AssertionError」的替身，实际 `invoke()` 被完全覆盖。
普通读重试配置故意保留为1，以验证生产 `modelOnce` 仍只发一次；SDK 自身仍使用生产代码的 `maxRetries(0)`。

请求核对包括：固定 system + 当前 USER 原文恰好两条消息、model=qwen-plus、temperature=0、max_tokens=128、
strict Schema 与冻结配置一致，以及无工具、历史、ID、上下文或 gold。含首尾空格的原文按原样发送。

## 预算和失败语义

正常场景采用冻结上限：36次、单次 body 4,096字节、总 body 147,456字节、并发1、零重试。
三个兼容性哨兵 S01→S13→S25 包含在36次内，随后其余 S ID 按文件顺序执行，G 对照不发模型协议请求。
故障场景只缩小上限，具体值写入 `run.json`，不改冻结配置。

有两种字节记录：

- `production_input_bytes`：生产代码交给 ExternalCallBudget 的 `ChatRequest.toString()` UTF-8 长度。
- `received_body_bytes` / `request_bytes`：回环 handler 收到的完整 HTTP JSON body 长度，用于评测传输预算。

handler 收到本地请求后、生成 fixture 响应前原子预占预算。释放只归还并发槽，不退回次数或字节。
body 超限时本地 HTTP 已发生，但 fixture 尚未接受；因此 sidecar 的 `loopback_requests` 与
`supplier_attempts` 分开。此时 results 的 attempts/request_bytes 为0，拒绝的原始大小及原因留在 raw 记录。
`supplier` 在本目录仅指协议替身，不表示外部供应商。

保持生产返回 reason 原样：直接抛 ExternalCallBudget.Limit 得到 OBSERVATION_BUDGET_EXHAUSTED；
回环网关拒绝预算并返回429时通常得到 OBSERVATION_UNAVAILABLE，具体预算拒绝原因另记 metadata。
不会将本地预算拒绝伪装成模型已返回结果。

任一生产失败 reason 出现后，后续题均记 SKIPPED（0次、0字节、无响应字段、明确原因），不重试或补跑。
超时场景把请求预算缩小为1,000ms、替身响应延迟设为1,500ms；响应未知时 usage 为 null。
正常请求等待 handler 完成再取记录，超时记录只短暂等待，避免迟到响应被算成本题成功或污染后题。
关闭后核实回环监听已停止。进程内 Ledger 自检还实际启动第二线程竞争并发槽。

## 运行

要求 Java17、Python、已缓存 Maven 依赖，以及本机已有的 `backend/target/printer-after-sales-0.0.1-SNAPSHOT.jar`。
JAR 仅用来提取 BOOT-INF/lib 依赖，业务类始终来自本次离线编译的 `backend/target/classes`。
没有下载依赖或创建应用实例的回退路径。缺少依赖时停止并保留错误。

在仓库根目录运行全套13个协议场景，输出目录必须全新：

```powershell
python -X utf8 validation/observation-semantics/adapter/run.py --scenario all --output .local-validation/observation-semantics-eval/e2_local_01
python -X utf8 validation/observation-semantics/adapter/check_runs.py --run-dir .local-validation/observation-semantics-eval/e2_local_01
```

再次运行请改用新目录名。入口拒绝覆盖、越界和输出根重定向，失败保留原目录。
`--scenario success` 可只跑正常协议链，`--prepare-only` 只编译，不启动 HTTP 替身。
全套场景共享一次构建，各自使用全新的 Java 进程和子目录。

```powershell
python -X utf8 -m unittest discover -s validation/observation-semantics/adapter -p test_run.py -v
python -X utf8 -m unittest discover -s validation/observation-semantics -p test_score.py -v
python -X utf8 validation/observation-semantics/verify.py
```

子进程仅继承基础路径类环境变量，不传模型密钥、代理或 JVM 注入选项。Maven 使用 `-o`；
Windows 的 mvn.CMD 通过完整工具路径调用，不使用 `shell=True`。不读取私有配置或回显环境。

## 场景预期

下表的有效记录与受控失败都是**协议替身结果**；受控失败为预期注入，不是测试断言失败。

| 场景 | 有效记录 | 受控失败 | SKIPPED | 接受的 fixture 次数 / 本地 HTTP 次数 |
|---|---:|---:|---:|---:|
| success | 36 | 0 | 0 | 36 /36 |
| invalid-json | 0 | 1 | 35 | 1 /1 |
| second-sentinel-invalid | 1 | 1 | 34 | 2 /2 |
| third-sentinel-invalid | 2 | 1 | 33 | 3 /3 |
| tool-response、finish-length、timeout（各自） | 0 | 1 | 35 | 1 /1 |
| http-401、http-429、http-503（各自） | 0 | 1 | 35 | 1 /1 |
| call-budget：上限2次 | 2 | 1 | 33 | 2 /2 |
| byte-budget：单次上限1字节 | 0 | 1 | 35 | 0 /1 |
| total-byte-budget：总上限收紧为首个 body 大小 | 1 | 1 | 34 | 1 /2 |

5项独立 Ledger 自检：单次字节边界、总字节边界、调用次数边界、第二线程并发拒绝、预占不退款/拒绝不增量。
它们在每个进程内重复执行，汇报时只称5项不同检查，不将13次重复执行包装成65项新增能力。

## 证据

每次运行写入新的 Git 忽略目录：

- `build.json`：Java 版本、本地依赖 JAR 和适配器 Java/Python 源码 SHA256。
- `results.json`：与冻结评分器兼容的36个 S ID，含完整 SKIPPED。
- `run.json`：冻结配置、SYSTEM/Schema 指纹、执行顺序、实际预算、原样生产 reason、0真实模型/0DB声明、监听关闭状态。
- `raw/Sxx.json`：去请求头的实际 body、完整 fixture 响应、生产计数字节、收发时间、预算拒绝或失败信息，原始文件另有 SHA256。
- `score.json`：现有评分器的离线输出，明确 synthetic_test。
- 日志与 `summary.json`；构建或运行异常另写 `failure.json`，不删除现场。

`check_runs.py` 会再核对冻结包、构建时源码指纹、逐题原始文件指纹和场景行为。
它不能独立证明任意外来文件的来源，本目录的0外呼边界还依赖可审查的 fixture-only 代码。
真实语义准确率、真实 DashScope strict Schema 兼容性、完整 Service 的字段误填率与数据库行为仍未测量。

本轮实际执行记录见 `E2_VERIFICATION.md`。
