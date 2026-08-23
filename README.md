# 电源柜调试记录

纯离线的电力现场调试日志安卓应用：馈电柜 / 直流屏等柜子调试时，记录测试内容与故障处理过程。**不申请网络权限，不上传任何数据**，全部数据存储于手机本地 SQLite 数据库。

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
  - 导出全部日志为标准 **.xlsx**（两个工作表：调试日志、故障记录；A4 打印适配——一页宽缩放、每页重复表头、冻结首行、列宽自适应；故障表横向打印）
  - JSON 备份 / 恢复（恢复前强提示覆盖）

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
     "schemaVersion": 1,
     "projects":      [{ "id","name","code","remark","createdAt" }],
     "cabinetTypes":  [{ "id","name","remark","createdAt" }],
     "candidateItems":[{ "id","typeId","content","createdAt" }],
     "instances":     [{ "id","projectId","typeId","name","deviceCode","location","installer","createdAt" }],
     "logs":          [{ "id","instanceId","circuit","testContent","tester","remark","createdAt","updatedAt" }],
     "faults":        [{ "id","logId","circuit","symptom","solution","occurredAt","resolvedAt","status" }]
   }
   ```
2. **复用路径 A（Kotlin 多端）**：`data/db/Entities.kt` 与 `Repository.kt` 无 Android UI 依赖（仅依赖 Room 与 kotlinx-coroutines）。桌面端可用 JVM + Room（SQLite JDBC）、网页端可移植为 SQLDelight/Exposed，按相同语义实现 Repository。
3. **复用路径 B（直连数据库）**：桌面工具直接打开导出的 `.db` 文件或读取 xlsx/JSON，表结构与上述模型一一对应。
4. 扩展新功能（如照片附件）时新增表并 `schemaVersion+1`，保持旧字段不动即可双向兼容。

## 隐私声明

本应用 Manifest 未声明任何网络权限，无 SDK 统计、无第三方服务；数据仅在用户主动执行“备份/导出”时由系统文件选择器写入用户指定位置。
