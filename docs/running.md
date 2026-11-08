# 运行、配置与恢复

## 前提与构建

需要 Java 17、Maven 3.9+、Python 3.10+、Docker Linux containers 及 Compose v2。Python 脚本只使用标准库。当前完成 Windows 本机验证，其他平台需自行验证。

仓库根目录执行 `mvn -B -ntp -f backend/pom.xml package`。默认 JAR 是 `backend/target/printer-after-sales-0.0.1-SNAPSHOT.jar`，也可用 `--jar <路径>` 指定构建包。初次拉取固定 digest 镜像需要网络与足够磁盘空间，依赖与 Java 建议至少预留 4 GB 可用内存。

## 新实例

```sh
python -X utf8 scripts/portfolio_runtime.py init --mode offline --instance my-offline-demo
python -X utf8 scripts/portfolio_runtime.py publish-sample --instance my-offline-demo
python -X utf8 scripts/portfolio_runtime.py start --instance my-offline-demo --open
python -X utf8 scripts/portfolio_runtime.py accounts --instance my-offline-demo
```

`init` 复制并绑定 JAR 哈希，创建新 Compose 项目、持久卷、随机依赖密码和动态本机端口；初始化管理员后，通过正常 HTTP 完成邮箱绑定、客户注册、客服邀请与设备录入。邮箱只在 Mailpit 捕获，演示设备及订单为虚构。初始化本身不发布知识。

`publish-sample` 上传自编 CC0 文档，读取分块预览并发布。这是对自编资料的自动检查，不称为独立人工审阅。真实模式发布会产生 Embedding 用量。

相同实例名重复 `init` 会拒绝。中断可使用 `seed --instance <名称>` 继续尚未完成步骤；若恰在服务端成功而进度未写入时中断，应先核查已有记录，不能删除数据掩盖。多实例命令始终带 `--instance`，避免选错最近实例。

## 真实 AI

```sh
# .local-validation 由离线初始化创建，也可自行创建此私有目录
# 将 deploy/portfolio/real.env.sample 复制为 .local-validation/real.env
# 只在该私有文件中填写 AI_API_KEY
python -X utf8 scripts/portfolio_runtime.py init --mode real --instance my-real-demo --config .local-validation/real.env
python -X utf8 scripts/portfolio_runtime.py publish-sample --instance my-real-demo
python -X utf8 scripts/portfolio_runtime.py start --instance my-real-demo --open
```

也可在进程环境提供 `AI_API_KEY` 或 `DASHSCOPE_API_KEY`。缺少密钥在资源创建前拒绝。适配器限定 DashScope HTTPS 地址，当前使用 `qwen-plus`、`text-embedding-v4` 1024 维及 `qwen3-rerank`。真实/离线模式使用不同实例；不要在已有向量索引上直接切换 Embedding 身份。

默认日上限 120 次模型调用及 1,200,000 输入 UTF-8 字节；每轮 12 次/120,000 字节，知识任务 30 次/300,000 字节，并发 2。Chat、改写、Embedding、重排和重试共享持久预算。字节不是 token，未知供应商用量和账单不能记为零。私有配置支持样例列出的日上限等键，不支持任意绕过门禁。

### 可选：等待现场补充信息时的受限语义

`ASSISTANT_OBSERVATION_SEMANTICS_ENABLED` 默认是 `false`。真实 AI 模式也不会自动开启。启用后，只在已获收集授权、正在等待 `extra` 的合格轮次调用分类器；每次分类只尝试一次、零重试，仍受现有调用、字节和超时预算限制。真实调用会产生供应商用量。

首次体验先用当前源码构建新包，再按上节流程新建独立的真实 AI 实例；将上节命令中所有 `my-real-demo` 统一替换为新的唯一实例名，例如 `my-observation-demo`。实例保存的是初始化时复制并绑定哈希的 JAR；重新构建 `backend/target` 不会自动更新已有实例。已有实例使用下文的升级流程，核对目标包与迁移后再启用。

停止指定实例应用：

```sh
python -X utf8 scripts/portfolio_runtime.py stop --instance my-observation-demo
```

在该实例私有的 `.local-validation/portfolio/my-observation-demo/runtime.json` 中，向现有 `env` 对象增加下面这一项，保留其余配置：

```json
{
  "ASSISTANT_OBSERVATION_SEMANTICS_ENABLED": "true"
}
```

再启动同一实例：

```sh
python -X utf8 scripts/portfolio_runtime.py start --instance my-observation-demo --open
```

运行器会清理终端环境中的 `ASSISTANT_` 变量，`init --config` 的白名单也不接受这个开关；有效入口是上述实例私有 `env`。应用已经运行时，`start` 会直接返回，因此修改配置后必须明确停止、重新启动。关闭时将值改为字符串 `"false"`，同样停止、重新启动。

该功能只帮助区分现场事实、提问和意图不明的表达；提问、分类失败或不明确时保持字段并澄清。它没有扩展其他字段，也不会替代用户确认或直接创建工单。行为边界见 [架构说明](architecture.md)，当前小规模验证及其限制见 [受限语义评测](evaluation.md#等待-extra-的受限语义分类2026-11-08)。

## 账号、邮件与日常启动

```sh
python -X utf8 scripts/portfolio_runtime.py status --instance my-real-demo
python -X utf8 scripts/portfolio_runtime.py accounts --instance my-real-demo
python -X utf8 scripts/portfolio_runtime.py inbox --instance my-real-demo
python -X utf8 scripts/portfolio_runtime.py stop --instance my-real-demo --dependencies
python -X utf8 scripts/portfolio_runtime.py start --instance my-real-demo
```

账号页含随机密码，收件箱含一次性链接，仅供本机使用。绑定邮箱需在发起绑定的同一浏览器会话完成；切换客户与客服可用独立浏览器配置。`inbox` 将捕获的固定站点链接转为当前本机地址，不发送邮件。Windows 的 `start-portfolio.cmd`、`stop-portfolio.cmd`、`portfolio-accounts.cmd`、`portfolio-inbox.cmd` 操作最近实例。

`stop` 仅停止本实例应用进程；加 `--dependencies` 停止本项目依赖容器，始终保留卷与数据。脚本不提供清库或删卷动作。所有端口绑定 `127.0.0.1`。网络选择避开已有 Docker 网络，但 `10.240.0.0/16` 与宿主/VPN 冲突时需自行安排地址，不保证任意网络即插即用。

## 包更新与私有状态

```sh
python -X utf8 scripts/portfolio_runtime.py upgrade --instance my-real-demo --jar path/to/new.jar
python -X utf8 scripts/portfolio_runtime.py start --instance my-real-demo
```

升级先检查新包保留所有旧 migration 的文件名与哈希，再停止应用、保留旧包并替换。缺失、改写历史迁移或降级在停止前拒绝。升级不自动重跑评测。正式使用前需要备份数据库、知识/照片/索引文件、配置和秘密，恢复需核对业务和引用；不可把复制 JAR 当作完整备份。

私有目录为 `.local-validation/portfolio/<实例>/`，包含 runtime.json、secrets、进程状态、发布回执和日志，已由 Git 忽略，不应对外分享。`PACKAGE_HASH_MISMATCH` 表示固定包被替换；`COMPOSE_FAILED` 查实例内 compose-errors.log；依赖就绪超时查 Docker 和 application.log。不要通过清空数据库解决不明故障。

此启动器用于本机作品演示。公网部署须另外验证 HTTPS、代理、邮件实收、秘密管理、访问控制和备份恢复。

## 外部邮件、通知及部署恢复

本机运行器的 offline/real 仅区分 AI 依赖。两种模式都将 SMTP 指向回环地址的 Mailpit，并关闭外部通知；`real` 不会启用 QQ 邮件或飞书。Mailpit 是接收应用测试邮件的本地 SMTP 服务，不会读取或抓取 QQ 收件箱。

指定历史版本在 2026-11-02 已完成 QQ 邮件 7 封和飞书群 2 条的本人实收验证；19 项部署恢复与故障检查也有历史通过记录。这些结果与本次最终包演示分开，版本和局限见 [验证记录](verification.md)。

可选外部渠道和默认停用的 n8n 受控模板见 [邮件与自动化](automation.md)。独立部署、restic 备份和空目标恢复的配套源码及准备要求见 [部署与恢复](deployment.md)。这些入口需要单独配置环境和私有凭据，不能仅修改演示实例的 SMTP 地址或导入模板就认为已经可用。
