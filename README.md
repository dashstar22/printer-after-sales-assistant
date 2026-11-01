# 纸序 · 打印机售后服务

面向客户和客服的打印机售后服务网站，提供设备查询、AI 咨询、报修资料收集、工单提交与处理进度跟踪。

## 主要功能

- 用户注册、登录、记住账号、14 天自动登录及主动退出撤销令牌。
- 查询本人设备与订单，服务端校验客户归属。
- 三层对话路由：规则处理、持久化会话状态、大模型工具调用。
- 按型号检索知识、展示来源引用，无依据时补问或转入报修。
- 逐项收集报修信息，暂停、恢复、取消，准备及修改草稿。
- 用户在页面明确确认后提交工单，客服接单、记录处理及关闭，客户查看进度。
- 桌面和移动端布局，低强度常驻动效，遵循系统减少动态效果偏好。

## 技术栈

Java 17、Spring Boot 4.1.2、Spring Security、MyBatis、Flyway、MySQL 8.4、LangChain4j、Qdrant。前端使用原生 JavaScript ES Modules，无需 Node.js 构建。

## 本地启动

准备 Java 17、Maven 3.9+、Docker（Windows 可使用 Docker Desktop），并在仓库根目录执行：

```powershell
Copy-Item backend/.env.example backend/.env
```

编辑 `backend/.env`，设置自己的 `MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD`。建议使用不同的随机十六进制密码。默认端口为应用 8080、MySQL 13306、Qdrant 16333/16334；请确认未被其他服务占用。`.env` 和运行数据不会提交到 Git。

Windows 双击 `start-local.cmd`，或执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-local.ps1
```

脚本构建后端、等待 Docker 依赖就绪，并在当前窗口运行应用。打开 <http://127.0.0.1:8080/app/>，使用“创建账号”注册普通客户。新账号没有预置设备。

macOS / Linux 或手动运行：

```sh
mvn -B -ntp -f backend/pom.xml package
docker compose --env-file backend/.env up -d --wait
java -jar backend/target/printer-after-sales-0.0.1-SNAPSHOT.jar
```

按 Ctrl+C 停止应用。停止基础服务可执行 `docker compose --env-file backend/.env stop`，数据卷保留。

### 本地样例账号与订单

如需体验客户与客服的完整操作，在 `backend/.env` 中分别填写 `SEED_CUSTOMER_A_PASSWORD`、`SEED_CUSTOMER_B_PASSWORD`、`SEED_STAFF_PASSWORD`（至少 12 字符，至多 72 个 UTF-8 字节），然后执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-local.ps1 -SeedDemo
```

也可在手动构建和启动依赖后运行 `java -jar backend/target/printer-after-sales-0.0.1-SNAPSHOT.jar --seed-demo`，再正常启动应用。账号分别为 `demo_customer_a`、`demo_customer_b` 和 `demo_staff`，密码由你配置，没有公开默认密码。已有账号不会被覆盖；样例设备与订单不是实际交易。

## AI 与知识库配置

默认 `AI_ENABLED=false`，构建、注册及普通工单功能不会触发模型调用。AI 客户端支持 OpenAI 兼容接口，现有接入使用 DashScope `qwen-plus` / `text-embedding-v4`。启用 AI 前，在私有环境配置供应商地址、模型、密钥以及自己的调用额度控制。

检索还需要已登记、提取并发布的知识资料、对应的 `KNOWLEDGE_VECTOR_COLLECTION`、Embedding 配置及正文聊天许可 `KNOWLEDGE_CHAT_ALLOWED_HASHES`。源码包含知识维护命令与许可校验；公开仓库不携带已发布知识数据、厂商手册或本机审核资料，克隆后不会自动拥有维修知识，也不会自动发布资料或调用供应商。本机既有每日额度网关与账号配置属于私有运行环境，不随此源码快照分发。

密钥放在未跟踪的 `backend/.env` 或进程环境中。此仓库提供本地运行配置；公网部署需另行配置 HTTPS、代理和运维策略。

## 测试

```sh
mvn -B -ntp -f backend/pom.xml test
```

默认运行单元测试，不连接模型供应商。`*IT.java` 为集成测试源码，按 POM 中对应 profile 显式执行，需要单独准备隔离数据库、知识资料及测试环境；部分历史资料相关用例依赖本地未分发夹具。

## 目录

```text
backend/src/main/java/            后端业务、认证、对话及知识检索
backend/src/main/resources/       页面资源、应用配置、数据库迁移
backend/src/test/                 单元及集成测试源码
backend/.env.example              不含凭证的配置模板
scripts/start-local.ps1           Windows 源码启动入口
compose.yaml                     MySQL / Qdrant 本地依赖
```

## 第三方资源

字体 Noto Sans SC、Manrope 使用 OFL；Motion、tsParticles 使用 MIT；Lucide 图标保留其原许可。许可证位于 `backend/src/main/resources/static/app/licenses/`，来源清单位于同级 `vendor/sources.json`。公开版采用原创 SVG 抽象背景。Maven 依赖按各自许可证使用。

本仓库只发布源码和必要运行文件，未包含内部规划、验收报告、调用记录、数据库备份、凭证或受限知识资料。
