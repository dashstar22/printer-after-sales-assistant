<div align="center">

<img src="docs/images/avatar.png" width="168" height="168" alt="纸序项目头像" />

<h1>纸序 · 打印机售后助手</h1>

<p><strong>有据可查的 AI 咨询，有迹可循的工单服务。</strong></p>

<p>从设备咨询到确认报修，再到客服处理与进度查回的 Java RAG 应用。</p>

<p>
  <a href="https://www.oracle.com/java/technologies/downloads/#java17"><img src="https://img.shields.io/badge/Java-17-E76F00?logo=openjdk&amp;logoColor=white" alt="Java 17" /></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring_Boot-4.1.2-6DB33F?logo=springboot&amp;logoColor=white" alt="Spring Boot 4.1.2" /></a>
  <a href="https://docs.langchain4j.dev/"><img src="https://img.shields.io/badge/AI-LangChain4j-7954A1" alt="LangChain4j" /></a>
  <a href="https://qdrant.tech/"><img src="https://img.shields.io/badge/RAG-Qdrant_%2B_Lucene-6D4A8C" alt="RAG with Qdrant and Lucene" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-3DA66D" alt="MIT License" /></a>
</p>

<p>
  <a href="#运行截图">运行截图</a> ·
  <a href="#功能">项目功能</a> ·
  <a href="#快速启动">快速启动</a> ·
  <a href="docs/architecture.md">架构说明</a> ·
  <a href="docs/evaluation.md">评测记录</a>
</p>

<p>
  作者：<a href="https://github.com/dashstar22">dashstar22</a> &nbsp;·&nbsp;
  QQ 邮箱：<a href="mailto:1025073706@qq.com">1025073706@qq.com</a>
</p>

</div>

---

## 项目介绍

一个可在本机复现的 Java AI 应用项目，把“查看设备 → 查阅资料 → 确认报修 → 客服处理 → 查询结果”连成完整流程。

项目侧重 RAG 与业务系统的连接：回答需要有适用资料和可展开的引用，写入工单需要用户在页面明确确认，依赖故障时仍能使用普通报修。设备、订单及随仓库分发的知识样例均为合成演示数据。

这是个人学习与作品展示项目，使用 AI 辅助开发，尚无真实客户采用或商业效果验证。

## 运行截图

以下图片来自实际运行页面，设备、订单和资料均为合成演示数据。AI 引用图展示已保存的真实模型回答；其余页面来自公开源码的离线运行实例，保留页面上的模式提示。画廊使用等比例缩略图，工单图预览处理时间线局部；点击图片可查看完整原图。

<p align="center">
  <a href="docs/images/customer-overview.jpg"><img src="docs/images/customer-overview.jpg" width="960" alt="客户服务首页：咨询、报修、工单进度与本人设备" /></a>
  <br />
  <sub>客户服务首页 · 从设备咨询到报修进度</sub>
</p>

<table>
  <tr>
    <th width="50%">有来源的 AI 回答</th>
    <th width="50%">知识资料管理</th>
  </tr>
  <tr>
    <td><a href="docs/images/ai-answer-with-citation.jpg"><img src="docs/images/ai-answer-with-citation-preview.jpg" alt="真实 AI 回答与展开的来源引用，资料为自编合成样例" width="100%" /></a></td>
    <td><a href="docs/images/knowledge-management.jpg"><img src="docs/images/knowledge-management-preview.jpg" alt="管理员查看资料目录、适用型号与有效版本" width="100%" /></a></td>
  </tr>
  <tr>
    <td align="center">查看回答依据、适用条件和完整原文</td>
    <td align="center">登记资料，核查分块，确认发布与生效</td>
  </tr>
  <tr>
    <th>客户工单时间线</th>
    <th>客服工单工作台</th>
  </tr>
  <tr>
    <td><a href="docs/images/customer-ticket.jpg"><img src="docs/images/customer-ticket-preview.jpg" alt="客户查询已关闭工单与实际处理时间线" width="100%" /></a></td>
    <td><a href="docs/images/staff-workbench.jpg"><img src="docs/images/staff-workbench-preview.jpg" alt="客服按状态筛选、查看与处理工单" width="100%" /></a></td>
  </tr>
  <tr>
    <td align="center">报修内容、负责人和处理结果持续可查</td>
    <td align="center">待接单、处理中、已关闭状态清晰可见</td>
  </tr>
</table>

<details>
  <summary>查看登录页面</summary>
  <p align="center"><a href="docs/images/service-login.jpg"><img src="docs/images/service-login.jpg" width="960" alt="纸序登录页，使用邮箱与密码进入售后服务" /></a></p>
</details>

## 功能

- **客户服务**：邮箱验证注册、登录与找回密码，查询本人设备，通过对话或普通表单整理报修内容，确认后提交工单，补充文字和私有照片，查看处理时间线。
- **客服工作台**：接单、追加处理记录和关闭工单；关联咨询保留建单时的上下文和引用，避免重复描述。
- **业务管理**：邀请客服、维护标准型号和别名、核实设备归属、CSV 导入预检与确认。
- **知识管理**：上传文本 PDF、DOCX、Markdown 或 FAQ，检查分块，异步发布、重试与停用；新版本双索引核验通过后才替换旧版本。
- **有据问答**：型号和有效版本过滤，向量与 BM25 双路召回，RRF 融合及重排，展示来源与原文；区分无依据、需要补充信息和服务故障。
- **邮件与通知**：支持账号验证邮件、MCP 只读查询与 n8n 固定通知流程。指定历史版本已完成 QQ 邮件及飞书群实收验证；本机演示使用 Mailpit 并关闭外部通知，可选配置见 [邮件与自动化](docs/automation.md)。
- **运行保护**：服务端权限、SQL 归属约束、确认快照、幂等建单、候选缓存复核、限流、外部模型预算与审计；另有隔离 HTTPS 部署、加密备份和空目标恢复演练。

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

缺少密钥会在创建资源前报错。真实与离线模式使用不同实例，避免混用索引身份。两种模式的账号邮件均通过 Mailpit 在本机捕获；真实 AI 模式不会自动切换到 QQ SMTP 或开启飞书通知。默认外部模型调用日上限为 120 次、1,200,000 输入 UTF-8 字节，这些限制不等于人民币费用上限。

示例问题：“PRINT-DEMO-01 显示 E17 时我应该怎么处理？”；资料未覆盖示例：“这台设备如何连接 Wi-Fi？”。

停止应用和依赖并保留数据：

```sh
python -X utf8 scripts/portfolio_runtime.py stop --instance my-offline-demo --dependencies
```

完整配置、邮件操作、重新启动和升级说明见 [运行手册](docs/running.md)。当前启动流程在 Windows 实测，其他操作系统尚未完成同等验证。公网部署需要另行配置 HTTPS、邮件、备份及访问策略。

## 验证与评测

以下是项目的主要验证结果；各项验证的版本、环境和详细记录见 [验证记录](docs/verification.md) 与 [评测说明](docs/evaluation.md)。

| 证据 | 结果与范围 |
|---|---|
| 最终实现的快速回归 | 344 通过、0 失败、36 项因需要专用环境跳过；跳过项有独立集成证据，详见评测说明 |
| 真实 AI 与业务链路 | 新空库初始化、样例发布、有据/无依据问答、确认及幂等建单、客服关闭、客户查回与重启保留通过 |
| QQ 邮件专项验收（2026-11-02） | 指定候选版本真实发送 7 封、本人实收 7 封并完成链接操作，覆盖绑定、找回、注册及客服邀请；合成旧数据使用独立副本验证 |
| 飞书群专项验收（2026-11-02） | MCP → n8n → Java 发送门禁 → 飞书测试群，超时提醒和日报各 1 条，均有本人实收确认；批次随后关闭 |
| 隔离部署与恢复（2026-11-02） | 指定候选版本的 19 项部署、故障、加密备份及空目标恢复检查通过，其中包含 10 项真实 MySQL 预算测试 |
| 40 问三配置对照 | ACC 组 RRF + 重排的必要语义单元覆盖@5 为 **77.45%**，MRR@5 为 **0.7765**；完整 gold 为 **11/17** |
| 公开 8 问回归 | 首次严格契约 6/8，修复后 7/8；题目、标注和执行器随仓库提供 |

必要语义单元覆盖@5 衡量前五条检索结果对回答所需证据的覆盖程度，是项目自定义指标；它与回答准确率及标准检索 Recall@5 的含义不同。40 问已用于开发和回归，77.45% 低于原设定的约 88.24% 目标。回答支持性采用 AI 审核，尚未进行独立人工盲审或真实客户试用。

公开仓库提供自编 CC0 样例、8 问回归题与执行器。40 问对照使用的厂商资料受分发限制，仓库提供聚合结果和评测口径，未包含完整语料，因此无法仅凭公开素材逐字复现该组分数。详见 [评测方法、结果与局限](docs/evaluation.md)。

QQ 邮件、飞书通知及部署恢复的专项验收使用指定历史候选版本；最终包演示的范围另行记录。外部通知模板和部署恢复源码均已提供，使用时需配置自己的凭证和独立环境。

本地回归入口：

```sh
python -X utf8 scripts/regression.py quick
python -m unittest discover -s scripts -p test_portfolio_runtime.py
# 独立中间件集成回归：需 Docker，会创建新容器，结束后停止并保留数据
python -X utf8 scripts/regression.py middleware
```

以上回归使用本机协议 fixture，不调用外部 AI；真实模型评测的运行方式和结果见 [评测说明](docs/evaluation.md)。

## 目录

```text
backend/                  Spring Boot 应用、页面、数据库迁移与测试
scripts/                  隔离运行器和回归入口
deploy/portfolio/         本机依赖及真实 AI 配置样例
deploy/linux/compose.yaml 本机配置复用的固定镜像与服务定义
automation/n8n/            默认停用、手动触发的受控通知模板
samples/portfolio/        自编 CC0 演示资料
validation/product-portfolio-holdout/  已公开的 8 问冻结集与执行器
docs/                     架构、运行与评测说明
```

## 联系作者

欢迎交流 Java、RAG 与 AI 应用开发，也欢迎通过 [Issues](https://github.com/dashstar22/printer-after-sales-assistant/issues) 反馈复现问题。

- GitHub：[dashstar22](https://github.com/dashstar22)
- QQ 邮箱：[1025073706@qq.com](mailto:1025073706@qq.com)

## 许可

自有源码按 [MIT License](LICENSE) 开源。第三方前端资源保留原许可与来源记录，见 [第三方说明](THIRD_PARTY_NOTICES.md)。合成样例与冻结题使用各自目录内的 CC0-1.0 声明。厂商名称、商标及用户另行导入的资料不因此获得许可；演示资料不提供真实维修或厂商服务承诺。
