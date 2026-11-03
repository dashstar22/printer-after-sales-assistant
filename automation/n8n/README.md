# 手动受控通知模板

公开使用 `controlled-overdue.json`（超时提醒）和 `controlled-summary.json`（日报）。模板保持停用，无 Schedule、无自动重试，不保存节点执行数据；凭证、步骤和批次策略均需单独配置。

发送经过 Java `/api/integrations/notification-deliveries/{id}/dispatch`，由 Java 固定目标、构造消息并记账，n8n 不直接调用飞书 webhook。配置、实收登记与停止方式见 [邮件与自动化](../../docs/automation.md)，历史验证范围见 [验证记录](../../docs/verification.md)。

完整本地开发仓库还保留早期 `overdue.json` / `summary.json` 作为历史模板输入，它们直接访问 webhook 的流程不作为当前公开外发入口，不随公开发布副本分发。受控模板由既有 `controlled_workflow` 转换逻辑导出，本次未修改旧模板或历史批准记录。
