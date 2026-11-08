# E3 本机转发验证记录（2026-11-08）

本轮完成受控真实模型执行入口，默认关闭。实际执行仅使用两个本机回环协议服务和固定假凭证；
真实模型调用 **0**、数据库访问 **0**，没有修改生产代码、POM 或冻结评测资产，没有提交或推送。
本记录证明入口、请求转发和停止控制的本机行为，不证明供应商连接或模型语义质量。

## 最终证据目录与命令

最终 E3 证据使用仓库根目录下的：

```text
.local-validation/observation-semantics-eval/e3_suite_20261108_02/
```

实际执行：

```powershell
python -X utf8 validation/observation-semantics/adapter/run_e3.py --scenario all --output .local-validation/observation-semantics-eval/e3_suite_20261108_02
python -X utf8 validation/observation-semantics/adapter/check_e3_runs.py --run-dir .local-validation/observation-semantics-eval/e3_suite_20261108_02
python -X utf8 validation/observation-semantics/adapter/run.py --scenario all --output .local-validation/observation-semantics-eval/e3_e2_regression_20261108_01
python -X utf8 validation/observation-semantics/adapter/check_runs.py --run-dir .local-validation/observation-semantics-eval/e3_e2_regression_20261108_01
python -X utf8 -m unittest discover -s validation/observation-semantics/adapter -p 'test_*.py' -v
python -X utf8 -m unittest discover -s validation/observation-semantics -p test_score.py -v
python -X utf8 validation/observation-semantics/verify.py
```

构建离线使用本地既有 Maven 依赖，Java 为 Temurin 17.0.18。没有启动 Spring 应用或 Docker，
没有建立数据库连接。所有本机监听在执行后关闭，最终 active=0、max_active≤1。

初轮目录 `e3_suite_20261108_01/` 保留原始现场；随后修正停止与 socket 注册之间的竞态，
旧目录的构建指纹与最终源码不一致，因此 checker 拒收旧证据。本记录只采用 `_02` 最终源码对应的运行结果。

## 实际通过、失败与跳过

| 验证范围 | PASS | FAIL | SKIP | 说明 |
| --- | ---: | ---: | ---: | --- |
| E3 本机转发协议场景 | 16 | 0 | 0 | 实际启动 SDK→网关→本机上游链路 |
| E2 兼容协议场景 | 13 | 0 | 0 | 共享执行器调整后的回归 |
| 适配器 Python 单元测试 | 28 | 0 | 1 | 共29项，其中新增 E3 的15项全通过 |
| 评分器单元测试 | 16 | 0 | 0 | 冻结评分规则回归 |
| 实际进程入口拒绝检查 | 2 | 0 | 0 | Python 缺少显式开关；Java 错误 manifest ack |

唯一单元测试跳过项为 `test_symlink_parent_cannot_escape_allowed_root`：本机当前用户无权创建目录符号链接。
`..` 越界、已有输出目录保护和模拟重定向拒绝测试通过，但不能代替这项真实 OS 测试。

每次 E3 进程还执行18项不同的地址、凭证和预算策略拒绝自检，以及5项不同的预算账本边界自检，均通过。
自检在多个场景重复执行，不将其乘以场景数当作新增能力。

## E3 场景明细

表中的受控失败和后续 SKIPPED 是故障注入的预期记录，不是测试失败或未执行的验证场景。

| 场景 | 有效替身记录 | 预期受控失败 | 后续 SKIPPED | 上游替身接收数 / 本地 SDK HTTP 数 |
| --- | ---: | ---: | ---: | ---: |
| success | 36 | 0 | 0 | 36 / 36 |
| invalid-json | 0 | 1 | 35 | 1 / 1 |
| second-sentinel-invalid | 1 | 1 | 34 | 2 / 2 |
| third-sentinel-invalid | 2 | 1 | 33 | 3 / 3 |
| tool-response | 0 | 1 | 35 | 1 / 1 |
| finish-length | 0 | 1 | 35 | 1 / 1 |
| timeout | 0 | 1 | 35 | 1 / 1 |
| http-401 | 0 | 1 | 35 | 1 / 1 |
| http-429 | 0 | 1 | 35 | 1 / 1 |
| http-503 | 0 | 1 | 35 | 1 / 1 |
| redirect | 0 | 1 | 35 | 1 / 1 |
| disconnect | 0 | 1 | 35 | 1 / 1 |
| call-budget | 2 | 1 | 33 | 2 / 2 |
| byte-budget | 0 | 1 | 35 | 0 / 1 |
| total-byte-budget | 1 | 1 | 34 | 1 / 2 |
| credential-echo | 0 | 1 | 35 | 1 / 1 |

16个场景合计54次本机上游接收、56次本地 SDK HTTP 请求；15条预期受控失败、519条预期 SKIPPED。
正常场景36个完整 JSON body 合计53,132字节，单次最大1,490字节；所有场景预占 body 合计79,682字节。
重定向陷阱接收0次，断连与 HTTP 错误均无重试。正常 chunked 响应与错误 Content-Length 响应均经过验证。
超时保留生产原因 `OBSERVATION_BUDGET_STOPPED`，网关字节拒绝保留生产原因 `OBSERVATION_UNAVAILABLE`，
生产预算替身的调用额度拒绝为 `OBSERVATION_BUDGET_EXHAUSTED`，没有为方便报告改写原因。

`upstream_requests` 的记录语义是已预占的上游尝试数，连接或 DNS 失败也计1；未来真实运行时不能据此声称供应商实际收到了相同次数。
本轮本机上游有独立接收记录，所以能核对实际接收次数。

## 已核对的控制与资产

- 请求：逐字节 body 哈希一致，生产解释器、提示词、Schema、原文和严格响应解析继续复用；不使用 gold 构造分类响应。
- 默认关闭：Python 无 enable/ack 时退出1，Java 错误 ack 时退出2；均在读取凭证、创建输出和建立上游前拒绝。
- 凭证：仅使用固定假密钥；SDK 只有本地占位 key，上游认证独立注入，不转发 Cookie 等入站头。直接或标准 JSON Unicode 转义的假密钥回显被拦截，证据中无密钥。
- 预算：冻结上限36次、每题5秒、整次300秒、单 body 4096字节、总147456字节、并发1、零重试；故障场景只缩小预算，不放宽。
- 停止：首个生产失败后关闭活动连接并停止接题，后续题明确 SKIPPED；不会追补、切模型或降低 Schema。
- 冻结资产：`verify.py` PASS，9个资产文件和14个生产源码指纹一致。manifest SHA256 为 `afb4701fcdc28da8f3c9f8a77efda1eb20c9c8496d4e67d7340ccb871fe7187e`。
- 工作区：对照开始时50个既有改动文件的哈希，45个不变，4个适配器文件按授权修改。另有 `纸序项目学习进度.md` 的并行更新，保留未触碰；本轮新增文件均位于 adapter。生产代码、既有配置和冻结资产未被本轮改写。

机器可读汇总保存为最终目录的 `verification.json`；`gate-checks.json` 保存两项实际入口拒绝结果。
每个场景保留 run.json、results.jsonl、raw、upstream.json 和评分记录；build.json 保存源码与依赖指纹。
`git diff --check` 和适配器未跟踪文件的 UTF-8、末尾换行、尾随空白检查均通过。

## 尚未验证的范围

真实 DashScope DNS/TLS、供应商 strict JSON Schema 兼容性、qwen-plus 别名实际版本、真实 Token 用量和费用均未验证。
固定循环分类的本机响应不能作为分类准确率、危险 FACT 提议率或真实字段误填率的证据。
本轮没有 HTTP 业务接口或数据库写入集成测试，也没有授权启用生产语义开关或执行真实36题评测。
