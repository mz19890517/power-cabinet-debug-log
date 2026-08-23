# AGENTS.md — AI 会话工作指引

安卓APP「电源柜调试记录」：电力现场调试日志工具（Kotlin + Room + 传统View）。
仓库即完整交付物：代码、CI、文档都在这里。**新会话请先读本文件再动手。**

## 构建方式

- 只通过 GitHub Actions 构建（push 到 main 自动触发，`gh run watch` 等结果）
- 本地无 gradle wrapper / Android SDK，不要尝试本地 assembleDebug
- 产物：Actions artifact `power-debug-debug-apk`；正式发布用 `gh release create vX.X <apk路径>`

## ⚠️ 签名（最重要的约束）

所有历史版本靠**同一签名**才能覆盖安装。签名材料：

- `app/signing/powerdebug.keystore.zip` —— keystore 的加密压缩包（已入库）
- **解压密码不在仓库里，需要时向项目所有者（用户本人）索取**
- CI 已配置 Secrets 自动解压+签名：`SIGNING_ZIP_PASSWORD` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD`
- 原始 `powerdebug.keystore` 被 .gitignore 排除，但项目所有者本机留有一份

规则：
1. 永远不要替换/删除 keystore，不要改动 alias(powerdebug) 或密码配置
2. 永远不要把任何密码明文写进代码、README 或提交信息
3. 若用户忘记密码：keystore 无法恢复，签名链断裂 = 全体用户需卸载重装。提醒用户平时自备份

## 数据库与备份格式

- Room schema 当前 version=5（迁移链 1→2→3→4→5 必须保持完整，禁止 fallbackToDestructiveMigration）
- JSON 备份 schemaVersion=5，字段名=数据库列名，是将来 PC/网页端的交换格式
- 新增表/字段：DB version+1 写纯SQL迁移 + 备份版本+1 + parseBackup/restoreJson/merge 三处同步 + README 记录变更说明
- 合并语义：UUID主键按 id 去重插入，同 id 冲突 updatedAt 新者胜，绝不删除本地数据

## 业务模型速查

projects → cabinet_types(候选池 candidate_items) → cabinet_instances → debug_logs → fault_records
planned_items：柜子实例的预选待测清单，三态 result（0未测/1通过/2未通过），未通过项复测✓才转绿。
测试员账号 tester_accounts + WebDAV 团队互通（util/WebDavSync.kt，快照 backup_<账号>.json）。
debuggers：调试员名单（v5新增），与登录账号无关，增/改/删全部要超级口令；日志测试人员默认带出最近用（SyncStore.lastDebugger），改名/删除不动历史日志。

## 其他约定

- 超级口令 mz9890517 可离线注册测试员（源码 SyncStore.kt 内，属产品功能非机密）
- UI 文案全部走 strings.xml；中文注释是本仓库惯例
- 版本发布节奏：功能完成→versionName/versionCode 递增→README 更新→push→CI 绿→下载 APK 放项目根目录→（重要版本）发 GitHub Release
