# 独立部署与备份恢复

本机体验优先使用 [运行器](running.md)。本页说明随源码提供的可选部署资产，不能直接套用到已有实例。指定历史候选包已有 19 项隔离演练通过记录，见 [验证记录](verification.md)，并不表示本轮重新执行或已部署生产环境。

## 随包文件

| 文件 | 用途 |
|---|---|
| `deploy/linux/compose.yaml` | 固定镜像、独立网络/卷、回环 HTTPS、secret 文件与默认关闭的外部能力 |
| `deploy/linux/Dockerfile`、`entrypoint.sh` | Java 17 非 root 镜像和私有 secret 文件读取 |
| `deploy/linux/Caddyfile` | HTTPS、管理接口来源限制 |
| `deploy/linux/settings.env.sample` | 空值配置样例，复制到私有目录后填写 |
| `deploy/linux/initialize.yaml` | 仅首次显式初始化管理员时使用，不纳入日常启动 |
| `deploy/linux/snapshot.py` | restic 加密备份、解密物化、空目标恢复 |
| `deploy/linux/check_compatibility.py` | 替换包前检查数据库迁移版本集合 |

这些文件已公开；镜像、restic 二进制、证书、秘密及备份仓库需自行准备。历史环境是 Windows Docker Desktop 的 Linux containers，非独立 Linux 主机；restic 版本为 0.19.1。

## 部署准备

使用全新且准确的 Compose project、私有配置目录和独立卷。不要复用演示或日常业务资源。先通过 Maven 构建，在独立镜像构建目录放入命名为 `app.jar` 的 JAR、Dockerfile 和 entrypoint.sh，再构建并记录不可变镜像 ID；`APP_IMAGE` 指向该镜像。Compose 不会替你构建应用。

`settings.env.sample` 的 `SITE_HOST`、`SECRETS_DIR`、`CADDYFILE` 均需填写。secret 文件包括 `mysql_password`、`mysql_root_password`、`redis_password`、`rabbit_password`、`smtp_password`、`ai_api_key`、`n8n_encryption_key`；首次初始化另用 `admin_init_password`。未启用的外部服务可使用空秘密文件，不能分发通用默认密码。秘密目录保持私有，挂载权限按容器 UID 配置。HTTPS 默认绑定回环，公网入口需要另行部署验证。

首次管理员初始化需要 `ADMIN_INIT_USERNAME`，仅该次加入 `initialize.yaml` 并执行应用 `--initialize-admin`。旧登录迁移模式、邮箱验证和密码需按实际环境完成；初始化成功不是账号已经可日常使用的证据。不要套用历史合成迁移结果关闭日常库入口。

AI 日预算默认 0，邮件与通知默认关闭。外部渠道配置见 [邮件与自动化](automation.md)。`--profile automation` 只启动 n8n，不会启用流程或通知；私有网络为 internal，实际外部投递需要明确且受限的网络安排。不得为方便测试直接放开管理来源或跳过 TLS 校验。

## 备份与恢复

`snapshot.py` 的私有配置 JSON 使用 `project`、`env_file`、`compose_files`、`secrets_dir`、`support_files`、`restic`、`repository`、`restic_password_file`。非 `printer-p9-` 前缀项目还须提供与 project 完全一致的 `operator_confirmed_project`；脚本核对容器 Compose 标签。

备份会停止 app、n8n、Qdrant，导出 MySQL，复制原件/照片/Lucene、静止 Qdrant、n8n 配置与数据库、SMTP 等秘密和加密密钥，再执行 restic 加密及 `check --read-data`。操作会影响实例状态，必须先核实资源并准备维护窗口。restic 密码文件与仓库分开，明文暂存和解密文件也按敏感资料保护。

以下为操作语法，占位符需替换为操作者核实后的私有路径：

```sh
python deploy/linux/snapshot.py backup --config <private-source.json> --path <new-private-staging>
python deploy/linux/snapshot.py materialize --config <private-source.json> --snapshot <snapshot-id> --path <new-private-restore-dir>
python deploy/linux/snapshot.py restore --config <private-empty-target.json> --path <verified-snapshot-folder>
```

恢复使用全新空数据库/卷，拒绝非空库及运行过的目标；导入前核对 hash，导入后逐表核对行数。脚本在打开入口前通过应用 `--identity-maintenance=invalidate-restored` 离线维护命令失效旧身份、未用链接和自动登录凭据；该命令的显式维护开关与操作引用由脚本传入。随后需以新会话核对账号归属、工单、照片、引用、有效索引及 n8n 解密。恢复不重发历史邮件，不自动重新 Embedding 全库。当前脚本完成后仍保留停写状态，需操作者检查后显式启动。

`check_compatibility.py --config <private-target.json> --jar <candidate.jar>` 拒绝缺少既有数据库版本的旧包；通过后仍需 Flyway 校验 checksum 和业务验收。不能把恢复快照说成生产版本回退通过，也不能直接降库或丢弃备份点之后的新写入。

历史演练没有验证异地容灾或公网 ACME；Caddy 证书缓存未纳入该快照，换主机后须重新签发并验证。以上操作均非本轮文档检查执行的命令。
