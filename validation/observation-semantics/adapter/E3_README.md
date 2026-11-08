# E3 受控真实模型入口与本机转发验证

当前状态：真实执行入口已实现，**默认关闭**；本轮仅运行本机转发协议替身，真实模型调用0、数据库访问0。
真实 DashScope HTTPS/TLS、供应商 strict JSON Schema 兼容性、模型分类质量和实际字段误填率均未验证。
父目录的固定集、gold、配置和 manifest 不变；仍使用36条分类题、三类各12条，16条门禁对照不发请求。
本目录 E2 的实现记录为历史证据；E3 改动与本轮结果见本页和 E3_VERIFICATION.md。

## 最短执行链

`run_e3.py` → 生产解释器（本地占位 key）→ 回环转发网关 → 固定 HTTPS 上游（或本机测试上游）。

解释器、SDK 请求构造、固定提示词、Schema 和严格响应解析沿用生产代码，不复制语义分类实现。
本轮只修改 validation 中的适配器，共享 E2 的执行顺序、结果格式、预算替身和失败 SKIPPED 记录；
不启动应用、不创建数据库连接，不依赖日常实例、预算数据库或 Docker。

新增 `EvaluationEndpoint` 隔离两种测试端点：E2 的直接 fixture、E3 的 `ForwardingGateway`。
E3 的 `ForwardingUpstreamFixture` 是第二个回环服务，用于验证网关实际发送了什么；它不读取真实密钥或 gold。
适配器新增文件和构建依赖哈希记录在每次全新输出目录的 build.json，原冻结 manifest 不重写。

## 默认关闭与凭证隔离

`run_e3.py` 默认 `--mode forward-fixture --scenario all`。真实执行必须同时满足：

1. 明确选择 `--mode real --scenario success`。
2. 提供 `--enable-real-model`。
3. `--ack-manifest` 精确等于当前冻结 manifest.json 的 SHA256。
4. 在当前进程私有环境设置专用 `OBSERVATION_EVAL_API_KEY`，8～4096个 ASCII 可见非空白字符。

这些检查先于凭证读取；缺少显式开关或指纹错误时，在创建输出和构建前拒绝。
`--prepare-only` 即使选择 real 也不读凭证、不启动评测。
Java 主类再次核对 real 模式、固定场景和 manifest，然后才读取标准输入中的密钥。
不读 AI_API_KEY、DASHSCOPE_API_KEY、.env、私有文件或已有应用配置；没有 URL 或内联密钥参数。

真实密钥从 Python 经 stdin 传给 Java，不放命令行、子进程环境或日志。
生产 SDK 只持有固定无权限占位 key，只能连接自己的回环网关。
网关重新构造上游请求头，凭证只出现在上游 Authorization；不转发入站 Authorization、Cookie、代理头或其他客户端头。
本机上游替身检查其收到的是固定假凭证，不是 SDK 占位值，磁盘只记录校验布尔值，不记录请求头。
若响应直接或通过合法 JSON 转义回显密钥，网关返回固定502并停止，不将原始回显正文交给SDK或写入证据。
异常只保留固定原因码和类型，不写异常正文；Python 对子进程输出也执行凭证回显拦截。

## 上游与传输边界

真实上游固定为：`https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`。
baseUrl 必须与冻结配置精确匹配；不接受用户提供的地址、端口、查询或其他模型。
包内测试工厂只接受 `127.0.0.1` 的 HTTP 随机端口、固定 completions 路径，CLI 无地址覆盖入口。

每次尝试只创建一次 TCP/SSL 连接并发送一次 POST，没有连接池重发、HTTP库重试、重定向、代理、Cookie或Authenticator回退。
HTTPS 使用 JDK 默认信任链和主机名校验，不提供 trust-all 或跳过证书校验选项。
正文按原字节转发，正常响应正文原样送回SDK；响应头不透传，307也不会把Location带给SDK。
请求截止在 expect 时建立，连接、TLS和每次读取共用剩余时间；生产请求预算到期时关闭活动 socket。
socket 注册与 halt 同步，停止后不再接受新尝试。每题最多5秒，整次300秒后停止启动新题。
正文最多64KiB、HTTP头最多16KiB，支持 Content-Length、chunked 和连接关闭定界；拒绝歧义长度、压缩、非法帧和超额响应。
这些是受限评测传输能力，不是通用 HTTP 代理。

## 模型、预算与记录

- 模型仍为 qwen-plus 别名、temperature=0、max_tokens=128、strict ObservationIntent Schema；供应商不可变快照未知。
- 最多36次上游尝试，每题一次，S01→S13→S25先执行且计入36次；并发1，SDK和转发均零重试。
- 完整 HTTP JSON body 单次≤4096字节，总≤147456字节；实际请求发送前原子预占，不退款。
- 最大输出配置之和4608 tokens；usage 缺失为 null，不推算费用。
- `production_input_bytes` 保留生产 ChatRequest.toString 计数，传输预算只按完整 body 计数，两者不混用。
- `upstream_requests` 名称沿用记录格式，含义是**已预占上游尝试数**，连接/DNS失败也计1，不证明供应商已收到请求。
- 本地收到但预算拒绝的请求，loopback_requests增加、upstream_requests不增加，拒绝大小留raw；失败记录仍保留生产原始reason。
- 任何生产失败后 halt；后续题全部显式 SKIPPED，不重试、换模型、降级Schema或补跑。

真实结果标 `real_model`，本机替身结果标 `synthetic_test`；状态 `COMPLETED_WITH_RECORDS` 只表示产出了可评分记录。
评分器输出的分类匹配与危险 FACT 提议率仍按父README定义；真实效果应单独分析，不能根据本机替身循环三类的分数判断。
预算失败、协议失败、真实误分类和未执行项保持分开。入口完成不授权开启生产语义开关。

## 本轮可复验命令：只使用本机替身

在仓库根目录选择全新输出目录：

```powershell
python -X utf8 validation/observation-semantics/adapter/run_e3.py --output .local-validation/observation-semantics-eval/e3_local_01
python -X utf8 validation/observation-semantics/adapter/check_e3_runs.py --run-dir .local-validation/observation-semantics-eval/e3_local_01
python -X utf8 -m unittest discover -s validation/observation-semantics/adapter -p test_e3_runner.py -v
```

无需模型密钥；命令默认16个本机转发场景。编译使用 Maven -o 与本地既有依赖，不下载依赖。
成功、非法正文、第二/第三哨兵失败、工具响应、LENGTH、超时、401/429/503、307、断连、调用限额、
单次/总字节限额和凭证回显都由替身确定性注入。正常响应使用 chunked，错误响应使用 Content-Length，验证不同定界。
总字节故障场景将上限缩小到1600字节；其余冻结预算不变。超时场景生产预算缩小至1秒、上游延迟1.5秒。
18项策略拒绝自检覆盖非允许真实地址、非允许测试地址、非法凭证和放宽预算，不建立上游连接。

既有 E2 可继续使用 run.py / check_runs.py。每轮先核对冻结资产、后离线编译当前生产类和适配器；
失败保留目录，不覆盖旧证据。真实模式本轮没有执行；未来用户明确授权后，才使用前三项显式参数和专用私有环境配置启动。

## 证据与限制

run.json 保存模式、0真实/0DB声明（或日后真实上游尝试数）、固定配置、监听关闭、预算/地址策略自检、
执行顺序、停止原因和raw哈希。raw记录当前原文、生产reason、HTTP状态、完整正文、转发正文指纹和usage，均无凭证头。
upstream.json 仅存在本机替身运行，保存接收计数、凭证注入校验、收到body指纹和重定向陷阱次数。
check_e3_runs.py 核对本地与上游的逐字节哈希相等、原文与Schema、零重试、预算、SKIP、凭证不落盘和源码指纹。

本轮没有使用真实凭证，也未测试供应商TLS连接、模型版本、真实Token用量或费用；不能将本地转发通过说成真实模型评测通过。
