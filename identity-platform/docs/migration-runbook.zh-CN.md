# TapCanvas 身份迁移、积分开账与恢复

此手册用于生成可核对的切换批次。本轮只在隔离环境导入合成账号，未导入实际业务用户、未切换正式登录或积分。正式操作必须记录目标环境、备份、审核结果与最终批次摘要。

## 0. 确定目标并备份

记录 Keycloak issuer、平台 URL、PostgreSQL 库/schema、TapCanvas MySQL 主机/库、Redis 命名空间、版本及客户端回调。禁止从另一个环境复制用户 subject、Client Secret 或企业成员标识。确认企业微信 CorpID/成员 ID 与旧接入确属同一命名空间；拿不到证明的记录列为冲突，不自动绑定。

备份 Keycloak 和平台 PostgreSQL、完整 TapCanvas MySQL、媒体对象存储/引用、部署配置及加密密钥。备份加密存放并记录 SHA256、时间、权限和恢复位置；包含密码哈希的导出按凭据保护。正式环境采用其原有备份工具，不把演练容器脚本直接指向正式库。

## 1. 只读审计与决策

TapCanvas 根目录，通过私密环境配置设置专用 `IDENTITY_AUDIT_DATABASE_URL`（只读账号，不从运行服务的 DATABASE_URL 自动取值）。以下占位值必须换为审核目标：

```powershell
node apps/hono-api/scripts/identity-audit.mjs --host <主机> --database <库名> --batch <批次> --output .tmp/<批次> --reset-cron "0 0 * * *"
```

`--reset-cron` 必须来自当前额度配置，不默认推测。工具要求上海时区每日唯一刷新时刻。输出包括：

- `report.json`：账号数量、异常、密码兼容性、在途任务及业务归属摘要。
- `private-export.json`：格式 2，包含凭据、身份、会员/额度/申请规则、在途 LOCAL/PLATFORM 权属、未确认结算、业务归属清单、sourceDigest 和 exportDigest。
- `review.template.json`：逐条 IMPORT/EXCLUDE、账号策略、邮箱验证证据、企业命名空间证据、备份与凭据冻结引用。

每条记录必须有决定和原因。重复邮箱/用户名、重复企业身份、未知密码格式等先修复并重新审计，或明确 EXCLUDE 保留原系统处理。禁用账号可以按禁用状态迁入，但不能开账。员工的当前会员、额度和申请配置缺失或冻结时阻止自动导入。临时会员到期后按审核的默认申请规则生效，不凭空改变个人每日额度。

已有 Keycloak 身份需 `existingSubject`、独立 `bindingEvidence` 和 `credentialAction=KEEP_VERIFIED_EXISTING`。平台已有账本历史时导入会返回 `existing_wallet_requires_review`，不得清空旧账绕过。此类账号需单独制定保留身份和封存账本的管理批次，不与首次无历史迁移混用。

## 2. 先切身份

在最终凭据快照前设置 `IDENTITY_MIGRATION_FREEZE_AUTH_WRITES=true`，暂停旧注册、改密、绑定写入。再次审计并确认快照差异；不要使用窗口前的过期密码导出。在 Keycloak `identity-platform` 中编译：

```powershell
python scripts/account_migration.py compile --export <private-export.json> --review <review.json> --output .local/<批次>.accounts.json
python scripts/account_migration.py apply --batch .local/<批次>.accounts.json --expected-digest <编译输出摘要> --portal <平台URL>
# 核对 dry-run 和目标后，实际执行的同一命令加 --execute
```

应用时同时设置该环境的 `IDENTITY_LOCAL_DIR`、`IDENTITY_KEYCLOAK_URL` 等配置，工具会比对 batch 中的 issuer。新 Keycloak 用户先禁用，写映射成功后才恢复审核启用状态；使用 Keycloak 实际生成的 ID，不假定 POST users 接受调用者指定 ID。中断后按原批次重跑，受保护的迁移标记和本地 journal 防止重复建号。

迁入账号保留原平台 ID（如已存在）、原 TapCanvas ID、权限和数据。新钱包为 `credit_active=false`，导入不会发积分。先以 `AUTH_LOGIN_MODE=oidc`、`IDENTITY_CREDIT_AUTHORITY=local` 验证原邮箱密码、企微、无邮箱员工、禁用/冲突用户及退出。平台 `migrationReady` 保持 false，防止未完成迁移的旧用户被当作新用户。

核对业务归属：

```powershell
node apps/hono-api/scripts/identity-ownership-verify.mjs --before <迁移前导出> --after <迁移后导出>
```

命令在 TapCanvas 根目录执行。旧 users.id、项目、画布、资产和任务归属必须存在且不改变；允许新增结果。整个原用户范围完成处理后，管理员才显式设置 TapCanvas 应用 `migrationReady=true` 开放真正新用户。单个冲突不能通过建新号掩盖。

## 3. 再切积分

暂停新付费任务和旧额度发放：

```dotenv
IDENTITY_MIGRATION_PAUSE_PAID_TASKS=true
IDENTITY_MIGRATION_PAUSE_GRANTS=true
```

同步停止旧的会员每日发放、延迟申请发放调度；保留任务恢复/结算 Worker。API 的服务端拦截是第二道保护。记录尚未完成的旧任务，每条明确 KEEP_LOCAL；这些任务仍结算原账本，不重复进入新余额。平台未确认结算必须先核对。旧积分与旧申请历史保留，只是不转入新账。

最终快照和审核文件需包含：账号批次摘要、最终 sourceDigest、备份引用、暂停新任务引用、停止旧发放引用、凭据核对引用、每账号 OPEN/EXCLUDE 与原因、旧任务 inFlightDisposition。

```powershell
python scripts/credit_cutover.py compile --accounts <账号批次> --journal <账号应用journal> --snapshot <最终导出> --review <开账审核> --output .local/<批次>.opening.json
python scripts/credit_cutover.py apply --batch .local/<批次>.opening.json --expected-digest <开账摘要> --portal <平台URL>
# dry-run 核对后，在相同命令加 --execute
```

开账请求按批次和账号生成稳定 UUID，重复执行不重发；不同事实冲突。员工按审核额度开当期账，个人初始积分来自独立配置，默认 0。余额结转固定为 0。

API 和 Worker 同时切 `IDENTITY_CREDIT_AUTHORITY=platform`，验证新任务标记 PLATFORM、历史任务标记 LOCAL。确认 portal / points/me / ledger 一致，再解除新任务暂停。保留原额度写入阻断，旧后台申请/刷新不再运行。凭据写入改由 Keycloak 管理，旧密码表不可继续接受改密。

## 4. 恢复边界

- 尚未产生新身份绑定/改密或平台消费：可在冻结窗口中核对版本和批次后恢复旧入口，保留迁移证据。
- 已发生新改密/绑定：不能直接回到旧密码快照。对账 Keycloak 及旧凭据变更，选择前向修复或经过用户验证的恢复流程，不能索取明文密码。
- 已发生新消费：停止新请求，按 app_id/operation_id、远端操作、MySQL outbox、任务结果逐笔核对；保留未决和退款记录。只改环境变量会制造双账，禁止这样回滚。
- 供应商状态未知：继续权威查询；不因切换或超时释放冻结，也不重生成。作品与审计只保留，不删除。

备份恢复到新库，验证后再决定切换连接；不在运行库覆盖恢复。恢复 schema 后还需恢复数据库角色/权限、密钥、域名、回调、会话存储和媒体引用，完成服务启动与认证/结算验收。

## 5. 本地隔离复现

演练独立端口：Keycloak 8280、平台 8281、API 8877、MySQL 53316、Redis 56379、邮件 SMTP 51025/UI 58025。PostgreSQL 使用独立 schema/业务角色；不操作用户已有 8180/8081 进程。

`prepare_rehearsal.py` 创建隔离配置；加载其 `process-env.json` 后运行初始化/客户端配置。`prepare_tapcanvas_rehearsal.py` 生成私密 run.env；只在指定测试库运行 fixture 与演练服务。现有 schema 的用户测试数据不得用 fixture 覆盖。

```powershell
python scripts/rehearsal_database_backup.py backup
python scripts/rehearsal_database_backup.py restore --backup .local/rehearsal/backup-<编号>
```

该工具只接受隔离 schema 与固定测试容器，恢复到新随机库、核对行数，并授予已存在的测试服务角色访问新库的权限，不会更改原库。它不自动切换运行服务。停止隔离的 8280/8281 服务后，可用 `run-rehearsal.ps1 keycloak -RecoveryBackup <备份目录>` 和相同参数的 `platform` 启动恢复实例；API 使用恢复库 URL 及 `IDENTITY_REHEARSAL_RECOVERY_DATABASE=<报告中的新库名>`，更换测试 Redis 命名空间。

本轮已在恢复的新库上重新启动 Keycloak、平台和真实 TapCanvas API，6 项浏览器/HTTP 验收全部通过。正式目标环境仍需恢复自身角色、密钥、媒体和部署配置，不能复用本地测试角色配置。
