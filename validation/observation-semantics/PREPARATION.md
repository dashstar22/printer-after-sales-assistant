# 本轮准备记录（2026-11-08）

状态：**评测资产准备完成，真实模型评测未执行**。本轮只新增本目录的评测数据、文档和离线工具，
编译输出与本机证据保存在 Git 忽略的 target / .local-validation 目录。
没有修改生产代码、模型开关、数据库、现有评测结果，也没有提交或推送。

## 实际执行与结果

| 检查 | 本轮实际结果 | 证明范围 |
|---|---|---|
| `mvn -o -B -ntp -f backend/pom.xml -DskipTests compile` | BUILD SUCCESS | 从当前源码重新编译；离线构建，无应用启动；构建有仓库既有弃用 API 警告 |
| `javac -encoding UTF-8 ... GateProbe.java` | 退出码0 | 本包离线探针可编译；依赖来自本地现有 JAR，未下载依赖 |
| `GateProbe` | **52 PASS / 0 FAIL** | 36 条语义题均可达候选；16 条对照符合现有门禁预期；不调用分类器或 Service |
| `python -X utf8 -m unittest discover -s validation/observation-semantics -p test_score.py -v` | **16 PASS / 0 FAIL / 0 ERROR / 0 SKIP** | 合成记录验证评分逻辑，包括危险提议分母、失败不能算正确 UNCLEAR、预算越界、畸形协议与跳过 |
| `python -X utf8 validation/observation-semantics/verify.py` | PASS（结果和 manifest 哈希见本机 verification.json） | 资产结构、固定题数、配置预算算术、资产与相关源码指纹 |
| 工作区保护检查与资产格式检查 | PASS（详见本机 verification.json） | 既有文件内容/删除状态保留；仅增加本评测目录；无空白格式错误 |

`GateProbe` 第一次核查为 51 PASS /1 FAIL：原 S20「要不要先断电再取纸」命中现有 safeContent 的「不要」，
不适合作为合格分类题。冻结前将它替换为「取纸之前需要先断电吗」，重跑为上表 52/52。
这是评测输入可达性修订，没有放宽生产门禁，也没有看到任何真实模型输出后修改 gold。

逐题 gold 已按当前 SYSTEM 审阅；主观性最高的 S34 猜想句保留 speculation 标签和保守定义说明。
这属于 AI 辅助审查，不是独立人工盲审。

## 本轮未执行

- 真实模型、Embedding、Rerank、邮件和通知调用：**0 次**。
- 数据库集成、业务 HTTP 执行、Docker、日常实例访问：未执行。
- 真实分类准确率、危险 FACT 提议率：**尚无模型结果，未测量**。
- 实际 extra 字段误填率、完整 Service 的分类器误调用率：**未测量**。
- 完整 HTTP 序列化请求体字节、真实供应商 strict JSON Schema 兼容性：未验证。
- 真实执行适配器：尚未实现；执行计划见 README 的 E2/E3。

本包未生成真实 results.json；单元测试中的 synthetic_test 记录不能作为模型质量结果。
本轮没有重跑前序的单元/协议替身整套回归或 HTTP/MySQL 集成，不将历史通过数混入本轮统计。

本机证据目录：`.local-validation/observation-semantic-eval-prep/`。该目录被 Git 忽略，
verification.json 保存本轮命令结果、最终资产验证和工作区保护检查；不包含凭证。
