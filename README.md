# 纸序 · 打印机售后知识与工单助手

一个可在本机复现的 Java AI 应用项目，把“查看设备 → 查阅资料 → 确认报修 → 客服处理 → 查询结果”连成完整流程。

项目侧重 RAG 与业务系统的连接：回答需要有适用资料和可展开的引用，写入工单需要用户在页面明确确认，依赖故障时仍能使用普通报修。设备、订单及随仓库分发的知识样例均为合成演示数据。

这是个人学习与作品展示项目，使用 AI 辅助开发，尚无真实客户采用或商业效果验证。

## 功能

- **客户服务**：邮箱验证注册、登录与找回密码，查询本人设备，通过对话或普通表单整理报修内容，确认后提交工单，补充文字和私有照片，查看处理时间线。
- **客服工作台**：接单、追加处理记录和关闭工单；关联咨询保留建单时的上下文和引用，避免重复描述。
- **业务管理**：邀请客服、维护标准型号和别名、核实设备归属、CSV 导入预检与确认。
- **知识管理**：上传文本 PDF、DOCX、Markdown 或 FAQ，检查分块，异步发布、重试与停用；新版本双索引核验通过后才替换旧版本。
- **有据问答**：型号和有效版本过滤，向量与 BM25 双路召回，RRF 融合及重排，展示来源与原文；区分无依据、需要补充信息和服务故障。
- **运行保护**：服务端权限、SQL 归属约束、确认快照、幂等建单、候选缓存复核、限流、外部模型预算与审计。MCP 只读工具及固定通知流程为可选功能，本机演示默认关闭通知。

## 技术栈与结构

Java 17 · Spring Boot · Spring Security · MyBatis · Flyway · LangChain4j · MySQL · Qdrant · Lucene · Redis · RabbitMQ。前端为原生 JavaScript ES Modules，无需 Node.js 构建。

```text
浏览器（客户 / 客服 / 管理员）
                │ HTTP + Session + CSRF
          Spring Boot 单体应用
           ├─ 账号、设备、报修与工单 ── MySQL
           ├─ 知识任务 / Outbox ── RabbitMQ
           ├─ 检索 ── Qdrant + Lucene ── RRF ── 重排
           ├─ 候选缓存与限流 ── Redis
           └─ 证据上下文 ── 聊天模型 ── 回答与引用校验
```

各组件的职责与失败边界见 [架构说明](docs/architecture.md)。

## 快速启动

准备 **Java 17、Maven 3.9+、Python 3.10+、Docker 与 Compose v2**，并启动 Docker。建议至少预留 4 GB 可用内存。以下命令在仓库根目录执行；Windows PowerShell 可将 `mvn` 写为 `mvn.cmd`。

```sh
mvn -B -ntp -f backend/pom.xml package
python -X utf8 scripts/portfolio_runtime.py init --mode offline --instance my-offline-demo
python -X utf8 scripts/portfolio_runtime.py publish-sample --instance my-offline-demo
python -X utf8 scripts/portfolio_runtime.py start --instance my-offline-demo --open
python -X utf8 scripts/portfolio_runtime.py accounts --instance my-offline-demo
```

初始化会创建独立 Compose 项目、随机密码、三个角色账号及演示设备，所有端口仅绑定本机。实际应用地址由启动输出给出；账号页仅在本机打开。相同实例名不能重复初始化，已有数据不会被覆盖。

**离线模式**使用确定性的向量与重排协议模拟，聊天模型明确返回不可用；适合体验业务流程、发布状态及故障回退，不能用来判断 AI 回答质量。

**真实 AI 模式**接入 DashScope `qwen-plus`、`text-embedding-v4`（1024 维）及 `qwen3-rerank`，需要自己的有效密钥，并会产生供应商用量：

```sh
# 先复制 deploy/portfolio/real.env.sample 到 .local-validation/real.env 并填写密钥
python -X utf8 scripts/portfolio_runtime.py init --mode real --instance my-real-demo --config .local-validation/real.env
python -X utf8 scripts/portfolio_runtime.py publish-sample --instance my-real-demo
python -X utf8 scripts/portfolio_runtime.py start --instance my-real-demo --open
python -X utf8 scripts/portfolio_runtime.py accounts --instance my-real-demo
```

缺少密钥会在创建资源前报错。真实与离线模式使用不同实例，避免混用索引身份。邮件通过 Mailpit 在本机捕获，不向真实邮箱投递；默认外部调用日上限为 120 次、1,200,000 输入 UTF-8 字节，这些限制不等于人民币费用上限。

示例问题：“PRINT-DEMO-01 显示 E17 时我应该怎么处理？”；资料未覆盖示例：“这台设备如何连接 Wi-Fi？”。

停止应用和依赖并保留数据：

```sh
python -X utf8 scripts/portfolio_runtime.py stop --instance my-offline-demo --dependencies
```

完整配置、邮件操作、重新启动和升级说明见 [运行手册](docs/running.md)。当前启动流程在 Windows 实测，其他操作系统尚未完成同等验证。公网部署需要另行配置 HTTPS、邮件、备份及访问策略。

## 验证与评测

项目保留真实调用的失败和分母，未通过项不因代码修复或重新评测而改写为历史成功。

| 证据 | 结果与范围 |
|---|---|
| 最终实现的快速回归 | 344 通过、0 失败、36 项因需要专用环境跳过；跳过项有独立集成证据，详见评测说明 |
| 真实 AI 与业务链路 | 新空库初始化、样例发布、有据/无依据问答、确认及幂等建单、客服关闭、客户查回与重启保留通过 |
| 40 问三配置对照 | 验收集上 RRF + 重排的必要语义单元覆盖@5 为 **77.45%**，MRR@5 为 **0.7765**；完整 gold 为 **11/17** |
| 可公开复现的 8 问 | 首次严格契约 6/8；修复后的已见回归 7/8，保留原 gold 及状态差异 |

77.45% 是本项目对“前五条是否覆盖必要证据单元”的统计，**不是回答准确率**，也不直接等同于标准检索 Recall@5。原约 88.24% 的质量门槛未达到；40 问均已用于回归，不能继续称为未见测试集。未做独立人工盲审或真实客户试用。

公开仓库提供自编 CC0 样例、8 问冻结题与执行器。原 40 问所用厂商资料因分发限制未包含在内，只公布聚合结果和评测口径，因此仅凭公开素材不能逐字复现该分数。详见 [评测方法、结果与局限](docs/evaluation.md)。

本地回归入口：

```sh
python -X utf8 scripts/regression.py quick
python -m unittest discover -s scripts -p test_portfolio_runtime.py
# 独立中间件集成回归：需 Docker，会创建新容器，结束后停止并保留数据
python -X utf8 scripts/regression.py middleware
```

这些回归使用本机协议 fixture，不调用外部 AI。真实模型质量需另行运行并审阅冻结题。

## 目录

```text
backend/                  Spring Boot 应用、页面、数据库迁移与测试
scripts/                  隔离运行器和回归入口
deploy/portfolio/         本机依赖及真实 AI 配置样例
deploy/linux/compose.yaml 本机配置复用的固定镜像与服务定义
samples/portfolio/        自编 CC0 演示资料
validation/product-portfolio-holdout/  已公开的 8 问冻结集与执行器
docs/                     架构、运行与评测说明
```

## 许可

自有源码按 [MIT License](LICENSE) 开源。第三方前端资源保留原许可与来源记录，见 [第三方说明](THIRD_PARTY_NOTICES.md)。合成样例与冻结题使用各自目录内的 CC0-1.0 声明。厂商名称、商标及用户另行导入的资料不因此获得许可；演示资料不提供真实维修或厂商服务承诺。
