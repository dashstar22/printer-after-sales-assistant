# 公开冻结回归集（8 问）

frozen-v1 提供自编 CC0 文档、问题、原 gold 及 freeze.json。首次执行后已暴露，所有复现都应视为公开回归。首跑与修复后结果及局限见 [评测说明](../../docs/evaluation.md)。

验证冻结文件不调用模型：

```sh
python validation/product-portfolio-holdout/run.py verify --package validation/product-portfolio-holdout/frozen-v1
```

真实执行先按运行手册创建并启动一个新的 real 实例。用 status 输出的完整 JAR SHA-256 替换以下占位符：

```sh
python validation/product-portfolio-holdout/run.py publish --package validation/product-portfolio-holdout/frozen-v1 --instance my-real-demo --expected-jar-sha <JAR_SHA256>
python validation/product-portfolio-holdout/run.py run --package validation/product-portfolio-holdout/frozen-v1 --instance my-real-demo --expected-jar-sha <JAR_SHA256>
```

会产生真实供应商用量。执行器要求预算余量、等待请求窗口并逐题限速，不自动重试，不改变 gold，不自动启动或升级实例。新本机执行的首次采集并不使公开题恢复为未见测试。

同一个实例后续回归使用 `run` 并追加 `--exposure SEEN_REPAIR_REGRESSION --regression-label <唯一小写标签>`；该模式保留前次记录，复用原题与 gold。不要覆盖 run.json 或改 gold 来获得零退出码。

原始响应、逐题状态、延迟与预算保存在实例私有目录。结构校验不能代替语义审阅，合理但与原 gold 不同的状态仍记录为严格不匹配。资料为 AI 自编并由 AI 核对，不是独立人工评测或客户试用。
