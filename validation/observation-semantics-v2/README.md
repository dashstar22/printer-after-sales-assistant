# 等待 extra 受限语义分类 v2

本包将首轮真实评测发现的问题固定为修复回归：保持生产 `kind` 三分类结构、模型和预算，
只允许固定的v2资产目录，继续复用公共评测适配器与生产解释器。生产默认开关保持关闭。
本文件定义冻结资产的身份；具体执行结果保存到新的私有输出目录，不回写本文件或gold。

## 原始证据与版本关系

- v1固定资产及manifest保持原字节。原36条语义题和16条门禁gold在本包原字节复用，属于公开、自编、已见回归。
- 首轮真实结果位于 `.local-validation/observation-semantics-eval/e3_real_first_20261108_01/`，26/36匹配、4/24危险FACT，不能被后续修复覆盖。
- `baseline-v1/` 保存v1 manifest引用的14个旧源码/配置文件，以及原字节manifest副本。它们只用于历史核验，不编译、不运行、不替代当前生产源码。
- 修改生产提示词后，直接运行v1 `verify.py` 会发现当前源码已偏离旧基线；这是历史身份差异。本包的 `verify.py --historical-v1-only` 用旧快照核对历史，而不是重写v1 manifest。
- v2 manifest单独冻结当前生产源码、本包文件和共享适配器/评分器指纹。任何后续变更均需保留原证据并另建版本，不能看到结果后静默改gold。

## 评测范围和预算

主回归仍为原36条：FACT、QUESTION、UNCLEAR各12条；门禁对照16条不发送模型。
`supplementary-pairs.jsonl` 另有6组成对、共12条针对性对照，覆盖分类指令与屏幕引用、伪造授权与实际操作、
转述建议与本人已尝试、混合问题与纯问题、条件与已发生结果、猜想与直接观察。
每条有预期分类、理由和风险标签；这些对照是修复时自编的公开样本，不能称为盲测或未见测试。
**补充对照本轮不额外调用真实模型，不并入36题分母，也不通过隐藏追加调用扩充预算。**

固定模型配置与v1相同：DashScope `qwen-plus`别名、LangChain4j 1.22.0、temperature=0、max_tokens=128、strict JSON Schema。
不可变模型快照未知，真实结果须据实记录响应model和usage。
config中沿用的 `planned_adapter.status=NOT_IMPLEMENTED` 等字段是v1冻结时的历史规划元数据，
不是当前适配器实现状态；当前公共E3入口已实现，v2仅固定资产目录并继续默认禁止真实外呼。

预算完全沿用v1：最多36次、S01→S13→S25优先且计入36次、并发1、零重试、每题最多5秒、整轮300秒启动截止、
完整JSON body单次最多4096字节/总147456字节。协议、服务或预算失败立即停止，后续题显式SKIPPED。
原始分类误判保留评分，不改题、重试、补跑、切模型或降低Schema。
修复后的提示词增加了规则和示例，必须先用本机替身捕获实际body并重新核对字节预算，不能沿用v1测得的body大小。
仅 `total-byte-budget` 故障注入场景按精确v2版本将替身总限额缩小到4096字节，
以容纳第一条较大的v2请求并在第二条触发拒绝；v1该故障限额仍为1600字节。
两版真实执行总限额始终为147456字节。checker要求实际观察到1条成功、1条受控失败和34条SKIPPED，
不能仅根据故障配置值宣称预算边界通过。

验收门槛仍为36条协议有效、至少33/36严格匹配、危险FACT 0/24、16/16门禁可达性核查。
通过小规模已见固定集不证明线上零误填；纯分类结果不测量实际字段误填率或完整Service误调用率。

## 固定入口与复用边界

`run.py` 只将公共 `adapter/run_e3.py` 的资产目录固定为本包；没有任意asset、上游URL或内联密钥参数。
生产解释器、请求构造、提示词、Schema和解析器均从当前代码复用；本包不复制语义实现。
公共Java端仅允许精确的v1或v2目录，仍由固定网关控制地址、凭证、传输、预算和停止。

默认 `forward-fixture / all`，真实执行仍必须显式选择 `real / success`、提供enable与本包manifest的准确SHA256，
通过门禁和构建后才读专用 `OBSERVATION_EVAL_API_KEY`。凭证经stdin传给Java，不出现在命令行、子进程环境或证据中。
本包不读取真实密钥文件，不访问数据库，不启动业务应用或Docker；外部调用预算使用进程内替身。

`score.py`直接复用v1评分函数；`check_runs.py`复用公共16场景协议checker，并独立检查共享适配器构建哈希。
checker只接受本机替身证据，不能用它将真实质量结果包装成协议PASS。

## 可执行的离线检查

从仓库根目录运行：

```powershell
python -X utf8 validation/observation-semantics-v2/verify.py
python -X utf8 validation/observation-semantics-v2/verify.py --historical-v1-only
python -X utf8 -m unittest discover -s validation/observation-semantics-v2 -p test_v2.py -v
```

使用全新输出目录运行本机转发替身，无需模型密钥：

```powershell
python -X utf8 validation/observation-semantics-v2/run.py --output .local-validation/observation-semantics-eval/v2_fixture_01
python -X utf8 validation/observation-semantics-v2/check_runs.py --run-dir .local-validation/observation-semantics-eval/v2_fixture_01
```

以上入口只执行主36题的16种本机故障场景；补充12条只冻结与结构核验，没有额外真实请求。
真实模式沿用公共入口的显式门禁，并且必须在获得本轮真实调用授权后执行；不在此提供默认外呼命令。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| cases.jsonl / gold.jsonl | 原36语义题、16门禁及原gold |
| supplementary-pairs.jsonl | 单独冻结的12条补充语义对照与理由；本轮不真实调用 |
| config.json / manifest.json | v2身份、原模型/预算与当前冻结指纹 |
| baseline-v1/ | 14个v1历史源码/配置快照和原manifest |
| run.py / check_runs.py | 固定v2资产的公共执行器与checker薄包装 |
| score.py / verify.py | 公共评分复用、v2当前指纹和v1历史验证 |
| test_v2.py | 包装入口、专用门禁、历史和补充资产边界单测；不执行模型/DB |
