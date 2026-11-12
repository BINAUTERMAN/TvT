# Gitee 主更新源

当前主源为 `binauterman/TvT`，备用源为 GitHub `BINAUTERMAN/TvT`。发行说明和点星链接继续使用 GitHub。

## 首次配置

1. 保持 Gitee 仓库公开，且已有默认分支。
2. 在 Gitee 创建可向该仓库发布发行版和附件的个人令牌。
3. 在 GitHub 仓库的 **Settings → Secrets and variables → Actions → New repository secret** 中新增 `GITEE_TOKEN`，将令牌填入 Secret。不要把令牌写进代码、配置文件、APK 或聊天。
4. 按现有流程发布 GitHub Release。流水线随后将同一份 APK 和 `update-manifest.json` 同步到 Gitee，同一版本使用相同版本标签（本次为 `5.4`）。后续发布不同代码时须递增版本，避免覆盖已发布版本。

发布脚本先创建预览发行版，上传两份附件并通过匿名下载校验后才转为稳定发行版。已存在的稳定发行版只检查，不覆盖。若同步失败，修复权限或容量问题后重跑任务；GitHub 上已发布的原始文件会被下载用于同步。

仅补同步已有发行版时，在 Actions 中运行 **Gitee release sync**，输入原始 GitHub 发行标签。该任务下载原始 APK 和清单，使用原发行说明，不重新构建或覆盖 GitHub 发行版。

可选：在 GitHub Actions Variables 中设置 `GITEE_REPOSITORY`，或本地构建时设置 `TING_GITEE_REPOSITORY`，以覆盖配置的 Gitee 仓库。Android 签名仍使用现有发布密钥。

## 客户端行为

- 更新检测优先请求 Gitee；无稳定发行版、缺少附件、访问限制、验证页或网络失败时尝试 GitHub。
- Gitee 下载因网络或 HTTP 错误失败时，至多自动切换一次同标签、同文件名的 GitHub 附件，安装前仍核对原清单中的 SHA-256、包名、签名和安装序号。
- 来源改变后，旧来源的检查缓存失效。检查频率继续沿用原有设置，不增加后台轮询。
- 首个 Gitee 发行版尚未发布时，会使用 GitHub 备用源。Gitee 登录态和令牌不会下发给客户端。
- 尚未包含该改动的旧版本需要先安装一次使用原发布签名的新版本，才能启用 Gitee 更新。

官方文档：[创建发行版](https://help.gitee.com/repository/release/create)、[发行版下载路由](https://blog.gitee.com/2022/08/18/update/)。
