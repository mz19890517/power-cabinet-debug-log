# 电源柜调试记录

面向电力现场（馈电柜 / 直流屏等柜子调试）的调试日志安卓应用。数据存储于手机本地 SQLite；**仅在用户主动配置并使用 WebDAV 同步时才访问自己指定的服务器**，不集成任何第三方 SDK。

## 功能总览

- **项目制管理**：以工程项目为根，一个项目包含一台或多台柜子
- **柜子类型（模板）**：如“直流馈线柜”，每个类型维护一份**预选测试项候选池**；保存日志时测试内容自动沉淀进候选池（自动去重），支持单条删除、批量粘贴导入
- **柜子实例**：现场实际设备（如“1号直流馈线屏”），隶属某项目、绑定某类型；同型号柜子共用同一套候选池。字段：名称必填，设备编号 / 安装位置 / **安装人员** 选填
- **调试日志**：
  - 项目→实例 两级定位后，自动加载该类型候选池，勾选后一键填充测试内容（可手改）
  - 回路号自由输入 + 历史回路联想下拉，留空即整柜测试
  - 每条日志可挂多条故障：故障回路 / 问题现象 / 解决方法 / 发生时间（默认当前可改）/ 解决完成时间 / 状态（待处理↔已解决）
  - 日志编辑、删除（级联删故障）
- **查询筛选**：项目 / 类型 / 实例 三级联动 + 故障状态（含待处理 / 已全解决）+ 回路号筛选 + 全文搜索（测试内容、备注、测试人、故障现象、解决方法）
- **数据工具**：
  - 导出全部日志为标准 **.xlsx**（两个工作表：调试日志、故障记录；A4 打印适配——一页宽缩放、每页重复表头、冻结首行、列宽自适应；故障表横向打印；日志表含创建/修改账号列）
  - JSON 备份 / 恢复（恢复前强提示覆盖；兼容 v1 旧备份自动迁移）
- **测试账号与 WebDAV 同步**：
  - 登录验证即 WebDAV 连通性（PROPFIND + Basic 认证），无独立账号体系
  - 登录后：新建日志自动绑定"测试人员"，每条日志记录**创建账号/修改账号**并在列表、编辑页、导出 Excel 中展示
  - **超级口令** `mz9890517`：在登录框密码栏输入即可离线直接注册本地测试员（免服务器）
  - 同步策略：按账号分文件存快照 `backup_<账号>.json`；上传=整库快照覆盖云端自己那份；**下载=智能合并**——按 UUID 主键去重、同 ID 冲突保留 `updatedAt` 较新者、绝不删除本机数据
  - 可选"保存后自动上传"开关（静默失败不打扰）；支持 http 明文（内网 NAS 场景）与中文路径
  - 局限：删除操作不参与同步（合并不会把远端已删数据带回本机已删状态之外的理解：远端快照里没有的记录不会被从本机删除）

## 多人协作说明

两台手机各自离线记录同一面柜子后同步互不覆盖（各传各的账号文件）；任一设备"下载并合并"即可把对方新增的日志/故障并入本机；同一台柜子的基础信息（实例名等）被两人先后修改时，以修改时间新的为准。若需彻底避免冲突，约定：基础档案（项目/类型/实例）只由一人维护。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin |
| 最低系统 | Android 9 (API 28) |
| UI | 传统 View + Material 3（浅色工业风），ViewBinding |
| 数据库 | Room (SQLite)，外键级联删除 |
| Excel | 零依赖自实现 OOXML 生成器（`core/XlsxWriter.kt`） |
| 备份 | `org.json` 序列化 |

## 构建

```bash
# Android Studio 直接打开构建，或命令行：
./gradlew assembleDebug        # 需要 JDK 17
```

也可直接使用 GitHub Actions 云端构建：push 后在 **Actions → Build APK → Artifacts** 下载 APK。

## 目录结构

```
app/src/main/java/com/fieldlog/powerdebug/
├── App.kt                     # Application：数据库/仓库单例
├── data/
│   ├── Repository.kt          # ★ 业务逻辑统一入口（候选池沉淀/级联删除/备份格式）
│   └── db/
│       ├── Entities.kt        # 6张表实体 + 查询POJO
│       ├── Daos.kt            # Room DAO
│       └── AppDatabase.kt
├── core/
│   └── XlsxWriter.kt          # 零依赖xlsx生成器(A4打印适配)
├── ui/
│   ├── MainActivity.kt        # 底部三页签：调试日志/设备管理/数据工具
│   ├── log/                   # 日志列表筛选 + 新建编辑 + 故障弹窗
│   ├── device/                # 项目/类型子页 + 项目详情(柜子) + 类型详情(候选池)
│   └── tools/                 # 导出/备份/恢复
└── util/DT.kt                 # 时间格式化与日期时间选择器
```

## 数据模型

```
Project 1─N CabinetInstance N─1 CabinetType 1─N CandidateItem(候选池)
                        │
                        └─1─N DebugLog 1─N FaultRecord
```

- 删除均为级联删除且删除前有数量警告弹窗
- 候选池 `(typeId, content)` 唯一索引兜底去重

## 后期电脑端 / 网页端接入指南（预留设计）

1. **JSON 即交换格式**：备份文件字段名 = 数据库列名（见下），任何平台解析该 JSON 即可获得全部业务数据。
   ```json
   {
     "app": "power-debug-log",
     "schemaVersion": 2,
     "projects":      [{ "id","name","code","remark","createdAt","updatedAt" }],
     "cabinetTypes":  [{ "id","name","remark","createdAt","updatedAt" }],
     "candidateItems":[{ "id","typeId","content","createdAt","updatedAt" }],
     "instances":     [{ "id","projectId","typeId","name","deviceCode","location","installer","createdAt","updatedAt" }],
     "logs":          [{ "id","instanceId","circuit","testContent","tester","remark","createdBy","updatedBy","createdAt","updatedAt" }],
     "faults":        [{ "id","logId","circuit","symptom","solution","occurredAt","resolvedAt","status","updatedAt" }]
   }
   ```
   - v2 起所有 `id` 为客户端生成的 UUID 字符串（多设备离线新增不撞主键）；`updatedAt` 为合并时钟。
   - 应用仍可导入 `schemaVersion: 1` 的旧备份（自动生成 UUID 并重映射引用）。
2. **复用路径 A（Kotlin 多端）**：`data/db/Entities.kt` 与 `Repository.kt` 无 Android UI 依赖（仅依赖 Room 与 kotlinx-coroutines）。桌面端可用 JVM + Room（SQLite JDBC）、网页端可移植为 SQLDelight/Exposed，按相同语义实现 Repository。
3. **复用路径 B（直连数据库）**：桌面工具直接打开导出的 `.db` 文件或读取 xlsx/JSON，表结构与上述模型一一对应。
4. 扩展新功能（如照片附件）时新增表并 `schemaVersion+1`，保持旧字段不动即可双向兼容。

## 隐私声明

应用声明了 INTERNET 权限，**仅用于用户主动配置的 WebDAV 同步**（PROPFIND 验证 / PUT 上传 / GET 下载），无 SDK 统计、无第三方服务、无遥测；不配置同步则完全不产生网络请求。WebDAV 账密明文存储于应用私有目录（按需求"低敏感信息不做高端加密"）。数据仅在用户主动执行"备份/导出"时由系统文件选择器写入用户指定位置。
