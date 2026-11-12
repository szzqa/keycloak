# 2026-11-12 本地实现与隔离验收记录

本轮使用当前 Keycloak 源码版本、Java 21、PostgreSQL 17、MySQL 8.4、Redis 和本地 Mailpit；未升级 Keycloak 大版本。外部企业微信使用本地 Postman 模拟，未连接真实企业或正式用户库。

## 已通过

| 验证 | 结果 | 证明范围 |
| --- | --- | --- |
| platform-server Maven package | 成功；14 项 PostgreSQL 集成测试 | 并发预占、重复/冲突事实、跨周期、员工/个人差异、审批/刷新、后付费差额、退款、取消墓碑、独立开账与规则到期 |
| 原密码原生兼容 | 1 项测试 | Keycloak 原生 PBKDF2-SHA256 对 TapCanvas UTF-8 盐及中文/emoji 密码的验证一致 |
| TapCanvas 回归 | 8 文件、71 项通过 | 文本后付费、零成本、任务 finalizer/终态 drain、原积分查询与登录配置的受影响路径 |
| 真实 MySQL + 平台 HTTP | 2 项通过 | 平台已结算但响应丢失后重试只扣一次、结果保留、本地账单确认；未派发任务取消后迟到预占被拒绝 |
| 迁移 Python 单元测试 | 7 项通过 | 编码、原 ID/默认零赠送、摘要篡改、归属证据、冲突、开账冻结证据及完成批次不覆盖后续账号变更 |
| 只读审计/归属工具 | 3 项通过 | 重复身份、禁止推测策略、上海每日刷新规则、记录丢失/转移检查 |
| 浏览器 + HTTP | 6 项通过 | 注册/验证/找回/旧会话撤销、原密码/ID/项目、门户 SSO/CSRF/退出、无邮箱企微、邮箱与企微同账号同策略、员工停用、禁用账号、错误 audience/权限、开账门槛和幂等 |
| 回调安全补测 | 通过 | 已用回调重放与伪造 state/code 均返回 400，原有效会话仍正常 |
| Postman 本地集合 | lint 0 问题；4 请求/4 断言通过 | 无身份访问用户、映射、预占和开账接口被拒绝；执行使用 --no-report-events |
| OpenAPI 结构 | 27 路径，0 未解析引用 | YAML 可解析、内部 $ref 有效；不等于完整远端规则 lint |
| TapCanvas API/Web | 类型检查通过，两端构建成功 | 当前代码可编译；API dist、Web dist 已生成 |
| MySQL 增量升级 | 通过 | 使用原 baseline 的预占/额度表及外键，升级后旧 amount=37/LOCAL/桶关联保留；新标识大小写区分，平台桶允许 null |

浏览器测试使用真实 Keycloak SPI、平台后端和真实 Hono API；API 回调真实返回 302。TapCanvas Web 未启动，所以未把回跳地址视作已渲染 React 页面。

## 迁移和恢复演练

合成原账号：邮箱密码用户、无邮箱的已授权企微员工、禁用邮箱用户。真实执行账号批次及重复应用，得到原 TapCanvas users.id；最新格式 2 导出在 Node/Python 之间的规范摘要一致，并可编译审核批次。

前后只读快照核对通过：3 个旧用户、3 个项目、3 个画布、3 个资产及审计基线中的 7 条历史结果仍有相同归属；后续新结果允许追加。最终没有在途或未确认的测试结算。该结果只覆盖合成演练数据，不代表正式数据已经迁移。

新账号导入后钱包未开通，消费拒绝；执行受控开账后才可预占。相同开账重复请求不发第二笔额度，不同事实返回冲突。新账不导入旧余额。

备份同时覆盖独立 Keycloak/platform PostgreSQL schema 与完整测试 MySQL，文件有 SHA256 清单。恢复至新库 `identity_recovery_64a16857ab7e`：平台用户/映射/操作/流水计数分别为 4/3/14/40，6 张关键产品表记录数一致。随后恢复测试服务角色权限，以新库启动 Keycloak、平台及真实 API，**同一套 6 项浏览器/HTTP 验收全部通过**。原数据库未覆盖。

凭据、私密批次、日志和截图保存在 Git 忽略目录：

- `.local/rehearsal/browser-run.log`、`recovery-browser-run.log`、`binding-browser-run.log`、`replay-browser-run.log`。
- `.local/rehearsal/accounts-v2.batch.json`、`migration-rehearsal-v2.json`：已实际应用的合成批次与 journal。
- `.local/rehearsal/accounts-v3.batch.json`：当前格式 2 源导出的编译结果，未重复导入旧批次用户。
- `.local/rehearsal/backup-49c8baae659f/manifest.json`、`recovery-report.json`：备份与恢复证明。
- TapCanvas `.tmp/rehearsal-v3-before`、`.tmp/rehearsal-v3-after`：私密快照与归属核对来源。
- TapCanvas `.tmp/identity-rehearsal/mysql-upgrade-report.json`、`api-build.log`、`web-build.log`。

构建产物：`platform-server/target/identity-platform-server-1.0.0-SNAPSHOT.jar`、SPI/theme JAR，以及 TapCanvas 的 `apps/hono-api/dist`、`apps/web/dist`。Windows 下曾因运行中的测试 API 占用 Prisma DLL 导致构建失败，停止该测试进程后重新构建成功，未停止用户原服务。

验收后已停止本轮创建的 Keycloak/平台/API 演练进程和企微模拟器，8280/8281/8877/9099 端口释放；用户原 8180/8081 实例未变。独立 MySQL/Redis/Mailpit 测试容器停止保留，备份与测试库未删除；共享 PostgreSQL 容器保留运行。

## 未计为通过的项目

- **TapCanvas React 浏览器验收**：自动审批拒绝前端开发服务启动，未提供具体原因。已完成真实后端/回调/门户及 Web 类型检查和构建，仍需在用户启动的前端上做页面验收。
- **Postman 远端 OpenAPI 规则 lint**：未登录时规则服务返回 401，本地集合 lint/执行与结构引用检查已通过；未发布云端内容。
- **graphify 更新**：按仓库要求尝试 `graphify update .`，当前环境无该命令，知识图谱未更新。
- **生产环境接入/切流**：真实企微命名空间、CorpID/应用权限与回调、正式 SMTP、域名、部署密钥、正式账号审核批次均未执行或验证。IDM 和其他真实产品保持后续范围。
- 门户为单实例会话实现；多副本共享会话/退出索引尚不在本轮上线能力内。Web 构建仍报告原有较大 chunk，未为此做无关重构。

## 计划复查结论

P0–P4 的本地实现、P5 页面代码及平台门户验收、P6 构建/迁移/恢复工具与隔离服务恢复验收已交付。原账号和业务数据、单策略、分阶段切换、任务唯一计费和结果保留均有代码与测试支撑。TapCanvas React 浏览器验收、正式目标审核与切流明确保留，不将代码实现等同于已正式上线。
