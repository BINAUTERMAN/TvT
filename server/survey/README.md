# TvT 问卷服务

适用于新网 Linux 服务器（systemd）与 Node.js 24 LTS，无第三方运行时依赖。部署包包含独立 Vue 问卷、JSON 接口、加密存储及 systemd 配置，不包含账号、答案、域名凭据或密钥。

## 专用服务器：公网 IP 直连

当前采用此模式，无需 Cloudflare 或域名。Nginx 监听公网 443，使用公开受信任的 IP 地址证书；Node.js 仅监听 `127.0.0.1:8787`。公网 80 只服务 ACME 证书验证，其他路径拒绝。表单界面保留在 App 内，公网仅开放 `POST /api/feedback`。

- `SURVEY_PUBLIC_ORIGIN=https://实际公网IP`，`SURVEY_PROXY_MODE=direct`，`SURVEY_SERVE_FORM=false`。Nginx 使用 `nginx-direct.conf.template`，将 `PUBLIC_IP` 替换为实际 IPv4 地址。
- Nginx 必须覆盖 `X-Real-IP` 为真实连接地址，清除客户端传来的 `CF-Connecting-IP` 和 `X-Forwarded-For`；后端只信任本机代理提供的该字段，防止伪造 IP 绕过限流。禁止公开 8787 端口。
- 每 IP 每秒 1 个请求（短时突发 3 个）、全局每秒 5 个请求（突发 20 个）；连接数每 IP 4、全局 128。后端另设每 IP 每小时 10 次、全局每分钟 300 次的上限。共享网络用户可能受同 IP 限制，按真实提交量谨慎调整。
- 业务进程使用专用低权限用户，最大内存 256MB、CPU 50%、32 个任务。数据库及加密密钥均不在 Web 目录内，公开端点不支持读取、查询、导出或上传文件。
- SSH 使用管理员本机密钥，确认新连接成功后关闭密码登录和转发；防火墙只允许必要的 SSH、ACME 80 和 HTTPS 443。更新防火墙前保留会话并安排自动回滚，验证新的密钥连接可用后撤销回滚。
- [Let's Encrypt 的 IP 地址证书](https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html)需要 Certbot 5.4+，通过 `certonly --preferred-profile shortlived --webroot --webroot-path /var/lib/tvt-acme --ip-address 实际IP` 申请。先用 staging 验证，再签发正式证书。使用 `--cert-name tvt-survey-ip` 与模板路径一致。有效期约 160 小时，必须启用包内证书续期 service/timer，每日两次检查并在续期后重新加载 Nginx。
- 官方 Node.js 24 运行时放在 `/opt/tvt-node24`，Certbot 虚拟环境放在 `/opt/tvt-certbot`；各 service 使用完整路径，不依赖交互式 shell 的 PATH。
- 无域名和 HTTPS 都不会隐藏公网 IP。该配置限制常见刷接口和资源消耗，不能保证防住分布式刷量、漏洞或带宽型 DDoS。匿名提交不能凭 APK 中的固定密钥可靠证明“仅官方 App 可提交”，因此不在 APK 内放共享秘密。

上线后从服务器本机、独立公网客户端及真实 Android 客户端分别测试有效证书、回执、重试、错误提交和非公开端口隔离，再配置 App 的正式提交地址。不得关闭证书校验或使用明文 HTTP 提交。

## 应用行为

当前本地预览版在“现在听”右上角提供“问卷预览（测试）”按钮：可预览邀请、全部题目和感谢页，明确标示“尚未提交”，不发送答案，也不消耗自动邀请次数。服务器接入后可移除此测试入口，保留设置内的正式问卷入口。

- 原生 `StarPrompt` 复用本机成功开始播放的计数，5 次 Star 提醒照旧，第 11 次触发问卷。暂停、恢复、切换音质不重复计数。不上传计数或曲目。
- 自动邀请只展示一次，拒绝、关闭后不重复打扰；“偏好设置 → 使用体验问卷”可手动打开。
- 旧版本计数最多只有 5，升级后从已保存的计数继续累计，无法恢复旧版本没有记录的播放次数。
- `config/survey.json` 默认没有地址，服务未部署前禁用自动邀请，手动页面明确提示尚未上线。部署成功后使用 `TVT_SURVEY_ENDPOINT=https://实际域名/api/feedback` 构建 APK，也可将不含凭据的 HTTPS 地址写入配置。
- 只有真实 JSON 回执与提交编号一致才显示成功。相同编号和相同内容重试不会重复保存；相同编号改写答案返回 409。

## 部署前准备

需要服务器 SSH 地址、系统版本、SSH 用户和密钥。公网 IP 直连按上节部署；下方 Cloudflare 方案仅作为有域名时的备选。不要把服务器密码、Cloudflare Token、Tunnel 凭据或数据库密钥写入仓库。

本地打包：`powershell -ExecutionPolicy Bypass -File scripts/package-survey.ps1`。上传 `artifacts/TvT-survey-server.zip` 并解压到 `/opt/tvt-survey`。先核对包内 `public/survey.html` 和 `public/assets/` 均存在。

在服务器通过发行版或 Node.js 官方渠道安装并验证 Node.js 24 LTS，安装 Cloudflare 官方 `cloudflared`。以下为准备命令，需根据服务器实际系统核对执行：

```sh
sudo useradd --system --home /var/lib/tvt-survey --shell /usr/sbin/nologin tvt-survey
sudo install -d -o tvt-survey -g tvt-survey -m 700 /var/lib/tvt-survey
sudo chown -R root:root /opt/tvt-survey
sudo chmod -R go-w /opt/tvt-survey
sudo install -o root -g root -m 600 /opt/tvt-survey/.env.example /etc/tvt-survey.env
```

在服务器本机生成密钥并直接写入受限文件，不在聊天、终端回显或命令历史中粘贴密钥：

```sh
sudo node --input-type=module -e 'import{randomBytes}from"node:crypto";import{readFileSync,writeFileSync}from"node:fs";const p="/etc/tvt-survey.env";writeFileSync(p,readFileSync(p,"utf8").replace("REPLACE_WITH_64_HEX_CHARACTERS",randomBytes(32).toString("hex")),{mode:0o600})'
sudoedit /etc/tvt-survey.env
```

把 `SURVEY_PUBLIC_ORIGIN` 改为实际域名（仅 `https://域名`，末尾不加斜杠）。核对 `command -v node` 并调整各 service 的 `ExecStart`。此密钥用于 AES-256-GCM 加密，丢失将无法恢复答案；更换需先安全导出并迁移数据。密钥与加密备份分开保存，服务器管理员有解密能力。

```sh
sudo install -m 644 /opt/tvt-survey/tvt-survey*.service /opt/tvt-survey/tvt-survey-purge.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tvt-survey.service tvt-survey-purge.timer
```

## Cloudflare 接入（备选）

选择此模式时设置 `SURVEY_PROXY_MODE=cloudflare`，需要发布独立网页时再设 `SURVEY_SERVE_FORM=true`。以下端口关闭要求仅适用于此模式。

采用 [Cloudflare Tunnel 官方流程](https://developers.cloudflare.com/tunnel/get-started/) 创建命名 Tunnel；使用 `cloudflared.example.yml` 配置单一域名到 `http://127.0.0.1:8787`，兜底 404。将 Tunnel 的 JSON 凭据仅存放在服务器受限路径；运行 `cloudflared tunnel route dns <Tunnel 名称> <实际域名>` 建立 DNS，安装并启动 cloudflared 服务。核对相应账号权限，不在应用内放任何 Cloudflare 密钥。

服务器业务进程仅监听 `127.0.0.1`，接口默认要求 `CF-Connecting-IP` 且 Host 与配置域名完全一致。**不要改为监听 0.0.0.0，也不要另建可绕过 Tunnel 的公网端口或公网反向代理。** 仅本机 cloudflared 可以连接业务端口；安全组和主机防火墙关闭公网 80、443、8787，SSH 仅放行管理员 IP，操作前保留现有 SSH 会话并确认备用登录可用。确保允许 Tunnel 所需的出站连接，参见 [Tunnel 防火墙文档](https://developers.cloudflare.com/tunnel/configuration/)。

Cloudflare 控制台在该主机名上启用账户支持的 DDoS、托管 WAF 和限流能力；按套餐核对可用规则。建议 `/api/feedback` 每 IP 每分钟 10 次封禁/限流，其余请求只允许 GET/HEAD（该接口允许 POST），不缓存问卷或 API。应用内为原生 HTTPS 请求，不应给 API 配置必须执行浏览器 JavaScript 的挑战；若确需 Turnstile，须另接移动端验证流程与服务端 Siteverify，不能把 HTML 挑战当成成功。

这些措施降低攻击面，不能承诺绝对安全或所有攻击均可拦截。中国大陆访问质量、域名备案及跨境处理安排需要由部署方结合服务器所在地与实际业务确认；上线前从实际网络测试。

## 数据处理

- 接口只接受明确列出的答案字段，评分为 1–5，文本最多 100 个 Unicode 字符，邮箱 254、展示 ID 40；五星隐藏的第二题、未选择“其他”时对应文本不会存储。
- 答案 AES-256-GCM 加密后保存 SQLite，参数化 SQL；公开接口没有读取、列举、修改或导出答案的能力，也没有管理网页。
- 不存音乐账号、令牌、播放记录、设备 ID 或原始 IP。IP 用于 Nginx 内存限流及后端小时限流，后端使用每次启动随机盐 HMAC，最多 10000 项；接口每 IP 每小时最多 10 次，全局每分钟最多 300 次。重启会清空对应内存限流。
- 请求最大 8KB，连接与请求有超时、连接数上限；数据库最多 100000 份答案，满额明确拒绝；服务以受限用户运行。
- 保留 180 天，启动、写入和本地导出时清理过期数据，每日 timer 处理空闲期（到期后至下一次清理前，最长额外约 24 小时）。加密备份同样需要按 180 天清理。
- 只有填写并主动提交且勾选说明的用户会传输答案。邮箱用于回复，展示 ID 获用户允许后可公开署名；正文和邮箱不公开。服务部署方应在正式隐私政策中披露实际供应商、联系方式和用户请求处理方式；仅备选 Cloudflare 模式涉及其网络代理处理。

本地受控导出（请把输出存到权限 600 的非公开路径，使用完删除；JSON 字符串按数据读取，避免未经转义插入 HTML 或表格公式）：

```sh
sudo sh -c 'umask 077; set -a; . /etc/tvt-survey.env; set +a; node /opt/tvt-survey/server.mjs --export > /root/tvt-feedback.jsonl'
```

## 上线验收

1. 默认直连模式 GET `/` 返回 404、`/api/feedback` GET 为 405；可选独立网页模式下再验证 `/` 返回问卷、深浅主题及窄屏正常。
2. 使用专用测试答案提交，核对真实 201 和相同编号回执，重试为 200，服务器本地受控解密确认字段；随后删除验收答案。
3. 非法评分、超长文本、未知字段、跨站 Origin、无效 Host、超过 8KB 请求均拒绝；验证 429。
4. 直连模式验证公网仅必要端口可用、8787 不能公网连接；Cloudflare 备选模式额外验证无法绕过 Tunnel。
5. 检查 `systemctl status tvt-survey tvt-survey-purge.timer tvt-survey-cert-renew.timer`，日志无请求正文/邮箱；备份恢复、密钥保管、证书续期和过期清理可用。
6. 配置 APK HTTPS 地址后，在真机完成触发、拒绝、填写、断网重试、前后台切换、返回键与真实服务提交测试。

未完成服务器、Cloudflare 及真实域名验证前，不能声称已上线或安全配置已生效。
