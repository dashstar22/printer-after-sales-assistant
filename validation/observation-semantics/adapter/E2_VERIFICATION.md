# E2 实际验证记录（2026-11-08）

状态：**本机协议替身适配器已实现并验证**。新增文件均在本 adapter 目录；生产代码、POM、父目录冻结资产、
gold 和 manifest 未改。真实模型调用0、数据库访问0，没有启动 Spring 应用或 Docker，没有提交或推送。
E3 的真实转发、供应商协议兼容性和真实语义质量评测均未实现/未执行。

## 最终有效运行

证据根目录：`.local-validation/observation-semantics-eval/e2_suite_20261108_02/`。
构建指纹见 build.json；完整逐题记录、原始 body、哈希和停止原因见各场景 run.json / results.json / raw/。
所有数据均为 `synthetic_test`。

实际执行：

```powershell
python -X utf8 validation/observation-semantics/adapter/run.py --scenario all --output .local-validation/observation-semantics-eval/e2_suite_20261108_02
python -X utf8 validation/observation-semantics/adapter/check_runs.py --run-dir .local-validation/observation-semantics-eval/e2_suite_20261108_02
python -X utf8 -m unittest discover -s validation/observation-semantics/adapter -p test_run.py -v
python -X utf8 -m unittest discover -s validation/observation-semantics -p test_score.py -v
python -X utf8 validation/observation-semantics/verify.py
```

| 验证 | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|
| 协议场景行为断言 | 13 | 0 | 0 | 0 |
| 不同的 Ledger 自检 | 5 | 0 | 0 | 0 |
| 新 runner 单元测试 | 13 | 0 | 0 | 1 |
| 既有评分器单元测试 | 16 | 0 | 0 | 0 |

runner 跳过项：真实 OS 目录 symlink 越界测试，本机普通用户创建目录符号链接时被拒绝。
实际 `..` 越界、既有目录保护，以及 mock 输出根重定向拒绝测试均通过；这不能替代被跳过的真实 OS symlink 路径。
冻结校验 PASS；Maven 离线 compile、适配器 javac 和格式检查通过。

## 请求、预算和停止证据

- 正常场景按 S01→S13→S25→其余 S ID 顺序执行，36条全部获得有效 fixture 响应；没有 G ID 或重复哨兵请求。
- 36条完整 HTTP JSON body：最小1,445字节、最大1,490字节、合计53,132字节。
  生产 `ChatRequest.toString()` 计数为最小1,484、最大1,529、合计54,536字节；两套字节没有混用。
- 每个实际请求都核对固定 SYSTEM、当前 USER 原文、model、temperature、max_tokens、strict Schema，且不含工具、历史、gold 或状态字段。
- 第一/第二/第三个哨兵协议失败时，分别只收到1/2/3次本地 HTTP，剩余35/34/33题均记 SKIPPED。
- 非法 JSON、工具响应、LENGTH 均由生产解释器返回 OBSERVATION_INVALID_RESPONSE。
- HTTP 401/429/503 各只有1次请求。即使普通读重试配置为1，生产 `modelOnce` 仍无重试。
- 超时场景只收到1次请求，实际 reason=OBSERVATION_BUDGET_STOPPED，后35题 SKIPPED，usage 保持未知；监听和线程随后关闭。
- 调用上限缩小为2时，第3题在 ExternalCallBudget 替身处被拦，生产返回 OBSERVATION_BUDGET_EXHAUSTED，实际只收到2次 HTTP。
- 单次 body 上限缩小为1字节时：本地 HTTP 1次、接受的 fixture 尝试0次；实际 reason=OBSERVATION_UNAVAILABLE，raw 另记 REQUEST_BYTES_LIMIT。
- 总 body 上限收紧为首个 body 大小时：本地 HTTP 2次、接受的 fixture 尝试1次；第二次被 TOTAL_BYTES_LIMIT 拒绝，后34题停止。
- 所有场景最终活跃槽为0、最大活跃槽≤1；每个进程实际关闭后再检查监听不可连接。
- Ledger 自检包含第二线程竞争、精确字节/次数边界、预占不退款和拒绝不增量。

最终13场景合计51次接受的 fixture 尝试、53次本地 HTTP。故障注入产生12条预期受控失败、414条预期 SKIPPED；
这些是停止行为测试数据，不是12项测试失败或414条真实模型跳过记录。正常 fixture 循环三类结果，与语义 gold 无关，
评分输出中的匹配数不得作为模型准确率。

## 联调修复与保留证据

首次启动目录 `e2_probe_20261108_01` 在 Maven 阶段发生 WinError2，Java-version 检查已完成，尚未产生模型协议调用。
原因是 Windows 的 mvn.CMD 未被 Python 短命令正确定位；已改为 shutil.which 解析完整工具路径，并补充两项单元测试。
失败目录和日志保留，没有自动重试或清理。

`e2_probe_20261108_02` 和 `e2_suite_20261108_01` 为中间验证。代码审查后修复正常响应完成与预算槽释放的短暂竞态：
读取记录等待同一请求完成，超时只短等；响应 body 写入成功后才登记响应元信息。最终以 _02 套件重新编译运行的结果为准，
不把重复执行次数计为新增测试能力。

工作区检查发现 `纸序项目学习进度.md` 在本任务期间另有并行更新；本任务没有写入或覆盖它。
其余既有文件状态/内容以及14个冻结源码指纹保持不变。最终保护检查和命令输出汇总保存在证据根目录的 verification.json。

本轮未执行数据库集成、完整业务 Service 或真实模型，因此真实分类质量、实际字段误填率仍未测量。
