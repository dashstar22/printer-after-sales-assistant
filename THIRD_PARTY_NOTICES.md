# 第三方资源与资料许可

根目录 MIT License 适用于本项目自有源码及对应公共说明。第三方组件、字体和资料保留原许可；厂商名称及商标的权利归各权利人所有。

| 随包前端资源 | 许可及本地声明 |
|---|---|
| Lucide 图标 | ISC / MIT，完整声明见 backend/src/main/resources/static/app/licenses/Lucide.txt |
| Noto Sans SC | SIL OFL 1.1，见同目录 Noto-Sans-SC-OFL.txt |
| Manrope | SIL OFL 1.1，见同目录 Manrope-OFL.txt |
| Motion | MIT，见同目录 Motion-MIT.txt |
| tsParticles | MIT，见同目录 tsParticles-MIT.txt |

版本、来源和 SHA-256 见 `backend/src/main/resources/static/app/vendor/sources.json`。公开版本使用原创抽象 SVG 背景。Java 依赖的版本由 `backend/pom.xml` 管理，通过 Maven 获取并遵守各组件许可；Docker 镜像按 `deploy/linux/compose.yaml` 的固定 digest 拉取，镜像及其内容不因本项目许可而改变许可。

README 头像 `docs/images/avatar.png` 由仓库维护者提供的图片裁切而成，仅作个人项目展示素材，不纳入项目源码的 MIT 授权范围。README 运行截图来自项目实际演示页面，其中显示的业务与知识内容为合成数据；截图不代表真实客户或商业采用。

`samples/portfolio/` 与 `validation/product-portfolio-holdout/frozen-v1/` 中自编合成资料使用各自 LICENSE.txt 的 CC0-1.0 声明。它们不对应真实设备、客户交易或厂商服务承诺。公开包不分发厂商手册或分发权未确认的素材；部署者导入自有资料须另行确认存储、分发以及各外部模型处理用途的许可。
