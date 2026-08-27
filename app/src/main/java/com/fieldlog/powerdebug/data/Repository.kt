package com.fieldlog.powerdebug.data

import androidx.room.withTransaction
import com.fieldlog.powerdebug.data.db.AppDatabase
import com.fieldlog.powerdebug.data.db.CabinetInstance
import com.fieldlog.powerdebug.data.db.CabinetType
import com.fieldlog.powerdebug.data.db.CandidateItem
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.Debugger
import com.fieldlog.powerdebug.data.db.DeletedItem
import com.fieldlog.powerdebug.data.db.FaultExportRow
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.InstanceRow
import com.fieldlog.powerdebug.data.db.InstanceStatusRow
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.data.db.PlannedItem
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.data.db.ProjectListItem
import com.fieldlog.powerdebug.data.db.TesterAccount
import com.fieldlog.powerdebug.data.db.TypeListItem
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 数据统计（工具页展示） */
data class Stats(
    val projects: Int,
    val types: Int,
    val instances: Int,
    val logs: Int,
    val pendingFaults: Int
)

/** 智能合并结果（各表 新增/更新 条数；appliedTombs=本机因墓碑实际删除的行数） */
data class MergeResult(
    var newProjects: Int = 0, var updProjects: Int = 0,
    var newTypes: Int = 0, var updTypes: Int = 0,
    var newCands: Int = 0,
    var newInstances: Int = 0, var updInstances: Int = 0,
    var newLogs: Int = 0, var updLogs: Int = 0,
    var newFaults: Int = 0, var updFaults: Int = 0,
    var newPlanned: Int = 0, var updPlanned: Int = 0,
    var newDebuggers: Int = 0, var updDebuggers: Int = 0,
    var appliedTombs: Int = 0
)

/** 删除日志时，对其完成的预选待测项的处置方式（用户弹窗二选一） */
enum class LogDeleteMode {
    /** 删除通过日志：恢复预选待测项 */
    RESTORE_PLANNED,
    /** 删除通过日志：连项删除预选待测项 */
    PURGE_PLANNED,
    /** 删除故障日志：删FaultRecord+关联消除日志+恢复PlannedItem */
    DELETE_FAULT,
    /** 删除消除日志：删消除日志+驳回FaultRecord */
    DELETE_RESOLUTION,
    /** 删除消除日志+故障日志：全删 */
    DELETE_RESOLUTION_PURGE
}

/** 备份文件解析结果：已统一为本机String主键的实体列表 */
private class ParsedBackup {
    val projects = mutableListOf<Project>()
    val types = mutableListOf<CabinetType>()
    val cands = mutableListOf<CandidateItem>()
    val instances = mutableListOf<CabinetInstance>()
    val logs = mutableListOf<DebugLog>()
    val faults = mutableListOf<FaultRecord>()
    val planned = mutableListOf<PlannedItem>()
    val debuggers = mutableListOf<Debugger>()
    val tombs = mutableListOf<DeletedItem>()
}

/**
 * 业务逻辑统一入口。
 * 后期电脑端/网页端移植时，按同样的语义实现本层即可复用全部业务规则
 * （候选池自动沉淀、级联删除、JSON 备份格式、智能合并等）。
 */
class Repository(private val db: AppDatabase) {

    private val projectDao = db.projectDao()
    private val typeDao = db.cabinetTypeDao()
    private val candDao = db.candidateItemDao()
    private val instanceDao = db.instanceDao()
    private val logDao = db.debugLogDao()
    private val faultDao = db.faultRecordDao()
    private val plannedDao = db.plannedItemDao()
    private val debuggerDao = db.debuggerDao()
    private val tombDao = db.deletedItemDao()

    private fun newId() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()

    /** 记删除墓碑（须在业务事务内调用）：该表该行已删除，随同步传播到全队 */
    private suspend fun markDeleted(tbl: String, itemId: String, t: Long = now()) {
        if (itemId.isNotBlank()) tombDao.insert(DeletedItem(id = newId(), tbl = tbl, itemId = itemId, deletedAt = t))
    }

    // ---------- 观察 ----------

    fun watchProjects(): Flow<List<Project>> = projectDao.watchAllAsFlow()
    fun watchProjectItems(): Flow<List<ProjectListItem>> = projectDao.watchListItemsAsFlow()
    fun watchTypeItems(): Flow<List<TypeListItem>> = typeDao.watchListItemsAsFlow()
    fun watchTypes(): Flow<List<CabinetType>> = typeDao.watchAllAsFlow()
    fun watchInstancesOf(projectId: String): Flow<List<CabinetInstance>> =
        instanceDao.watchByProjectAsFlow(projectId)
    fun watchInstancesWithStats(projectId: String): Flow<List<InstanceStatusRow>> =
        instanceDao.watchByProjectWithStatsAsFlow(projectId)
    fun watchPlannedOf(instanceId: String): Flow<List<PlannedItem>> =
        plannedDao.watchByInstanceAsFlow(instanceId)
    fun watchPool(typeId: String): Flow<List<CandidateItem>> = candDao.watchByTypeAsFlow(typeId)

    // ---------- 项目 ----------

    suspend fun getProject(id: String): Project? = projectDao.getByIdOnce(id)

    /** 新增或更新；返回最终id */
    suspend fun saveProject(p: Project): String {
        val row =
            if (p.id.isBlank()) p.copy(id = newId(), createdAt = now(), updatedAt = now())
            else p.copy(updatedAt = now())
        if (projectDao.getByIdOnce(row.id) == null) projectDao.insert(row) else projectDao.update(row)
        return row.id
    }

    /** 返回受影响柜子数（用于确认弹窗），-1 表示项目不存在。删除记墓碑随同步传播 */
    suspend fun deleteProject(id: String): Int {
        val p = projectDao.getByIdOnce(id) ?: return -1
        val cabinets = instanceDao.byProjectOnce(id).size
        db.withTransaction {
            markDeleted(DeletedItem.TBL_PROJECTS, p.id)
            projectDao.delete(p)
        }
        return cabinets
    }

    // ---------- 柜子类型与候选池 ----------

    suspend fun getType(id: String): CabinetType? = typeDao.getByIdOnce(id)
    suspend fun allTypes(): List<CabinetType> = typeDao.allOnce()

    suspend fun saveType(t: CabinetType): String {
        val row =
            if (t.id.isBlank()) t.copy(id = newId(), createdAt = now(), updatedAt = now())
            else t.copy(updatedAt = now())
        if (typeDao.getByIdOnce(row.id) == null) typeDao.insert(row) else typeDao.update(row)
        return row.id
    }

    /** 返回使用该类型的实例数（用于确认弹窗），-1 表示类型不存在。删除记墓碑随同步传播 */
    suspend fun deleteType(id: String): Int {
        val t = typeDao.getByIdOnce(id) ?: return -1
        val usage = instanceDao.byTypeWithProject(id).size
        db.withTransaction {
            markDeleted(DeletedItem.TBL_TYPES, t.id)
            typeDao.delete(t)
        }
        return usage
    }

    /**
     * 向候选池追加条目：自动按整行去重。
     * @return 新增条数
     */
    suspend fun addCandidatesFromText(typeId: String, text: String): Int {
        val existing = candDao.contentsOnce(typeId).map { it.trim() }.toHashSet()
        var added = 0
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it) }
            .forEach {
                candDao.insert(CandidateItem(id = newId(), typeId = typeId, content = it))
                added++
            }
        return added
    }

    /** 删除候选池条目，记墓碑随同步传播 */
    suspend fun deleteCandidate(item: CandidateItem) {
        db.withTransaction {
            markDeleted(DeletedItem.TBL_CANDS, item.id)
            candDao.delete(item)
        }
    }

    suspend fun instanceUsageOfType(typeId: String) = instanceDao.byTypeWithProject(typeId)

    // ---------- 柜子实例 ----------

    suspend fun getInstance(id: String): CabinetInstance? = instanceDao.getByIdOnce(id)
    suspend fun instancesOfProjectOnce(projectId: String) = instanceDao.byProjectOnce(projectId)

    suspend fun saveInstance(i: CabinetInstance): String {
        val t = now()
        val isNew = i.id.isBlank()
        val row =
            if (isNew) i.copy(id = newId(), createdAt = t, updatedAt = t)
            else i.copy(updatedAt = t)
        db.withTransaction {
            if (instanceDao.getByIdOnce(row.id) == null) instanceDao.insert(row) else instanceDao.update(row)
            // 新建柜子时把所属类型的候选池整份复制为该柜子的预选待测清单（快照式）
            if (isNew) seedPlannedFromPool(row.id, row.typeId, t)
        }
        return row.id
    }

    /** 把类型候选池中本柜还没有的条目补进预选清单，返回新增条数 */
    suspend fun seedPlannedFromPool(instanceId: String, typeId: String, ts: Long = now()): Int {
        val existing = plannedDao.contentsOnce(instanceId).map { it.trim() }.toHashSet()
        val fresh = mutableListOf<PlannedItem>()
        candDao.byTypeOnce(typeId).forEach { c ->
            val text = c.content.trim()
            if (text.isNotEmpty() && existing.add(text)) {
                fresh += PlannedItem(id = newId(), instanceId = instanceId, content = text, createdAt = ts, updatedAt = ts)
            }
        }
        plannedDao.insertAll(fresh)
        return fresh.size
    }

    /** 返回该柜子的日志条数（用于确认弹窗），-1 表示不存在。删除记墓碑随同步传播 */
    suspend fun deleteInstance(id: String): Int {
        val inst = instanceDao.getByIdOnce(id) ?: return -1
        val logs = logDao.countLogsOf(inst.id)
        db.withTransaction {
            markDeleted(DeletedItem.TBL_INSTANCES, inst.id)
            instanceDao.delete(inst)
        }
        return logs
    }

    // ---------- 调试日志 ----------

    suspend fun searchLogs(
        projectId: String, typeId: String, instanceId: String,
        status: Int, circuit: String, q: String
    ): List<LogListItem> = logDao.search(projectId, typeId, instanceId, status, circuit.trim(), q.trim())

    suspend fun getLogDetail(id: String): LogListItem? = logDao.getDetailOnce(id)

    suspend fun distinctCircuits(projectId: String, typeId: String) =
        logDao.distinctCircuits(projectId, typeId)

    /**
     * 保存日志（新建或编辑）并同步故障记录；
     * 同时把测试内容中出现的新行自动沉淀进对应柜子类型的候选池。
     * 预选待测联动：测试内容逐行（宽容匹配：忽略行尾标点）命中本柜未完成项 → 标记为"通过"并挂到本日志。
     * @return 本次自动标记为通过的预选项数量
     */
    suspend fun saveLog(log: DebugLog, faults: List<FaultRecord>, actor: String = ""): Int {
        val inst = instanceDao.getByIdOnce(log.instanceId)
            ?: throw IllegalArgumentException("柜子实例不存在")
        val t = now()
        var saved: DebugLog
        var markedCount = 0
        db.withTransaction {
            saved =
                if (log.id.isBlank())
                    log.copy(
                        id = newId(), createdAt = t, updatedAt = t,
                        createdBy = actor.ifBlank { log.createdBy },
                        updatedBy = actor.ifBlank { log.createdBy }
                    )
                else
                    log.copy(
                        updatedAt = t,
                        updatedBy = actor.ifBlank { log.updatedBy },
                        createdBy = log.createdBy.ifBlank { actor }
                    )
            if (log.id.isBlank()) logDao.insert(saved) else logDao.update(saved)
            faultDao.deleteForLog(saved.id)
            faults.forEach { f ->
                faultDao.insert(f.copy(id = f.id.ifBlank { newId() }, logId = saved.id, updatedAt = t))
            }
            // 预选待测联动：内容命中即标"通过"
            if (saved.testContent.isNotBlank()) {
                val lines = saved.testContent.split('\n').map(::normLine).filter { it.isNotEmpty() }.toHashSet()
                val hits = plannedDao.pendingForTestOnce(saved.instanceId)
                    .filter { normLine(it.content) in lines }
                if (hits.isNotEmpty()) {
                    // 命中项若上次是"未通过"，其关联故障随本次通过自动解决
                    val prevFaults = hits.map { it.faultId }.filter { it.isNotEmpty() }
                    if (prevFaults.isNotEmpty()) faultDao.resolveByIds(prevFaults, t)
                    plannedDao.setResult(hits.map { it.id }, PlannedItem.RESULT_PASS, t, saved.id, "")
                    markedCount = hits.size
                }
            }
            // 候选池自动沉淀：测试内容逐行 trim、去重后追加
            val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()
            saved.testContent.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && existing.add(it) }
                .forEach { candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = it)) }
        }
        return markedCount
    }

    /** 行规范化：去首尾空白与行尾常用标点，减少手打措辞差异导致的漏配 */
    private fun normLine(s: String): String =
        s.trim().trimEnd('。', '，', '；', '、', '！', '？', '!', '?', ',', ';', ' ')

    /** 该日志完成了哪些预选项（删除日志前的弹窗判断用） */
    suspend fun linkedPlannedOfLog(logId: String): List<PlannedItem> = plannedDao.forLogOnce(logId)

    /**
     * 删除日志。若该日志由「开始测试」生成或曾匹配完成预选项，
     * 由调用方先弹窗让用户选择：恢复为待测(重测) 或 连预选项一起删除(误添加)。
     * 记墓碑随同步传播；连项删除时每个被删的预选项也各自记墓碑。
     */
    suspend fun deleteLog(id: String, mode: LogDeleteMode) {
        val l = logDao.getByIdOnce(id) ?: return
        db.withTransaction {
            when (mode) {
                LogDeleteMode.RESTORE_PLANNED -> {
                    plannedDao.resetForLog(id, now())
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
                LogDeleteMode.PURGE_PLANNED -> {
                    plannedDao.forLogOnce(id).forEach { markDeleted(DeletedItem.TBL_PLANNED, it.id) }
                    plannedDao.deleteForLog(id)
                }
                LogDeleteMode.DELETE_FAULT -> {
                    // 删除故障日志：删FaultRecord + 关联消除日志 + 重新计算PlannedItem状态
                    val faults = faultDao.forLogOnce(id)
                    for (f in faults) {
                        // 查找并删除该故障的消除日志
                        val resLog = logDao.resolutionLogOf(l.instanceId, l.testContent, f.symptom)
                        if (resLog != null) {
                            markDeleted(DeletedItem.TBL_LOGS, resLog.id)
                            logDao.delete(resLog)
                        }
                        markDeleted(DeletedItem.TBL_FAULTS, f.id)
                    }
                    faultDao.deleteForLog(id)
                    recomputePlannedState(l.instanceId, l.testContent)
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
                LogDeleteMode.DELETE_RESOLUTION -> {
                    // 删除消除日志并驳回：删消除日志 + 恢复FaultRecord为pending
                    val matchedFaults = faultDao.byInstanceAndContentOnce(l.instanceId, l.testContent)
                        .filter { it.symptom == l.remark && it.status == FaultRecord.STATUS_RESOLVED }
                    for (f in matchedFaults) {
                        faultDao.unpassSingle(f.id)
                    }
                    // 检测未消除故障 → 驳回重测
                    recomputePlannedState(l.instanceId, l.testContent)
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
                LogDeleteMode.DELETE_RESOLUTION_PURGE -> {
                    // 删除消除日志+故障日志：全删
                    val matchedFaults = faultDao.byInstanceAndContentOnce(l.instanceId, l.testContent)
                        .filter { it.symptom == l.remark }
                    for (f in matchedFaults) {
                        val faultLog = logDao.getByIdOnce(f.logId)
                        if (faultLog != null) {
                            markDeleted(DeletedItem.TBL_LOGS, faultLog.id)
                            logDao.delete(faultLog)
                        }
                        markDeleted(DeletedItem.TBL_FAULTS, f.id)
                    }
                    faultDao.deleteAll(matchedFaults)
                    // 检测未消除故障 → 驳回重测
                    recomputePlannedState(l.instanceId, l.testContent)
                    markDeleted(DeletedItem.TBL_LOGS, l.id)
                    logDao.delete(l)
                }
            }
        }
        // 兜底自愈：删除日志后，凡故障记录已全部消失但仍标记「未通过」的幽灵测试项一律转「通过」
        healGhostFailures()
    }

    /**
     * 删除故障类日志后重新计算PlannedItem状态（数据一致性核心）：
     * 有未消除故障 → 删除通过日志 + 驳回为未测并写回faultId；
     * 无未消除故障 → 若有有效通过日志（或本已是通过态）则保持「测试通过」并清除失效faultId，
     *                  否则回退为待测（无故障且从未通过不该凭空标记为通过）。
     */
    private suspend fun recomputePlannedState(instanceId: String, content: String) {
        val pendingFaults = faultDao.pendingByInstanceAndContent(instanceId, content)
        val items = plannedDao.byInstanceAndContentOnce(instanceId, content)
        if (items.isEmpty()) return
        val t = now()
        if (pendingFaults.isNotEmpty()) {
            val faultIdStr = pendingFaults.joinToString(",") { it.id }
            for (item in items) {
                // 有故障的测试项不应有通过日志：存在则一并删除
                if (item.result == PlannedItem.RESULT_PASS && item.logId.isNotEmpty()) {
                    val passLog = logDao.getByIdOnce(item.logId)
                    if (passLog != null) {
                        markDeleted(DeletedItem.TBL_LOGS, passLog.id)
                        logDao.delete(passLog)
                    }
                }
                plannedDao.setResult(listOf(item.id), PlannedItem.RESULT_UNTESTED, 0L, "", faultIdStr)
            }
        } else {
            val decision = items.map { item ->
                val hasPassLog = item.logId.isNotEmpty() &&
                    logDao.getByIdOnce(item.logId)?.logType == DebugLog.LOG_TYPE_PASS
                if (item.result == PlannedItem.RESULT_PASS || hasPassLog) {
                    Triple(item.id, PlannedItem.RESULT_PASS, item.logId) // 保持/恢复「测试通过」
                } else {
                    Triple(item.id, PlannedItem.RESULT_UNTESTED, "")       // 从未通过 → 待测
                }
            }
            for ((itemId, result, logId) in decision) {
                val at = if (result == PlannedItem.RESULT_PASS) items.first { it.id == itemId }.doneAt else 0L
                plannedDao.setResult(listOf(itemId), result, at, logId, "")
            }
        }
    }

    /**
     * 兜底自愈（用户确认·简单粗暴原则）：凡测试项 faultId 指向的故障记录已全部不存在
     * （幽灵状态——界面显示「原因见日志」但故障列表为空，来源可以是删除日志、旧快照合并、
     * 历史版本残留等任何路径），一律纠正为「测试通过」。
     * 仅处理 faultId 完全悬空的项；故障记录仍存在（含已解决待复测）的项不动，
     * 遵守「故障标已解决不会自动过关，必须人工复测」的产品规则。
     */
    suspend fun healGhostFailures() {
        val items = plannedDao.allOnce().filter { it.faultId.isNotBlank() }
        if (items.isEmpty()) return
        val allIds = items.flatMap { it.faultId.split(",") }.filter { it.isNotEmpty() }.distinct()
        if (allIds.isEmpty()) return
        val existing = faultDao.byIdsOnce(allIds).map { it.id }.toHashSet()
        val t = now()
        for (item in items) {
            val ids = item.faultId.split(",").filter { it.isNotEmpty() }
            if (ids.isEmpty()) continue
            // faultId 全部找不到对应记录 → 该测试项实际已无故障 → 转为「测试通过」
            if (ids.all { it !in existing }) {
                plannedDao.setResult(
                    listOf(item.id), PlannedItem.RESULT_PASS,
                    if (item.doneAt > 0) item.doneAt else t,
                    item.logId, ""
                )
            }
        }
    }

    // ---------- 预选待测 ----------

    /** 向某柜预选清单追加自定义条目（多行输入自动按行拆分去重），返回新增条数 */
    suspend fun addPlannedFromText(instanceId: String, text: String): Int {
        val existing = plannedDao.contentsOnce(instanceId).map { it.trim() }.toHashSet()
        val fresh = mutableListOf<PlannedItem>()
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it) }
            .forEach { fresh += PlannedItem(id = newId(), instanceId = instanceId, content = it) }
        plannedDao.insertAll(fresh)
        return fresh.size
    }

    /** 从所属类型的候选池补充缺失条目，返回新增条数 */
    suspend fun syncPlannedFromPool(instanceId: String, typeId: String): Int =
        seedPlannedFromPool(instanceId, typeId)

    /**
     * 「加入常用模板」：把项目下所有柜子当前启用的预选待测项，
     * 沉淀为各自柜子类型的候选池条目（已有同名条目的自动跳过）。
     * 之后同类柜子即可在候选选择器里快速勾选；使用频次越高的项排序越靠前。
     * @return 新增候选条数
     */
    suspend fun saveProjectAsTemplate(projectId: String): Int {
        val insts = instanceDao.byProjectOnce(projectId)
        var added = 0
        db.withTransaction {
            insts.forEach { inst ->
                val contents = plannedDao.allOfInstanceOnce(inst.id)
                    .filter { it.enabled }
                    .map { it.content.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (contents.isEmpty()) return@forEach
                val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()
                contents.forEach { c ->
                    if (existing.add(c)) {
                        candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = c))
                        added++
                    }
                }
            }
        }
        return added
    }

    /**
     * 候选池按「使用频次」降序排列（频次=该类型全部柜子预选清单中出现次数；
     * 未用过的按创建时间排后）。返回 (候选, 频次) 列表，供候选选择器展示。
     */
    suspend fun candidatesByUsage(typeId: String): List<Pair<CandidateItem, Int>> {
        val usage = candDao.usageOfType(typeId).associate { it.content to it.cnt }
        return candDao.byTypeOnce(typeId)
            .sortedWith(
                compareByDescending<CandidateItem> { usage[it.content.trim()] ?: 0 }
                    .thenBy { it.createdAt }
            )
            .map { it to (usage[it.content.trim()] ?: 0) }
    }

    /** 全部柜子带项目名（跨柜拉取来源列表） */
    suspend fun allInstancesWithProject(): List<InstanceRow> = instanceDao.allWithProject()

    /**
     * 跨柜拉取预选待测：用来源柜的启用清单【整体覆盖】本柜清单。
     * 本柜原有条目（含停用的）全部删除并记墓碑随同步传播；
     * 来源项复制为新记录、重置为未测状态。
     * @return 覆盖后的清单条数
     */
    suspend fun pullPlannedFromCabinet(targetInstanceId: String, sourceInstanceId: String): Int {
        val src = plannedDao.allOfInstanceOnce(sourceInstanceId).filter { it.enabled }
        val t = now()
        var n = 0
        db.withTransaction {
            val olds = plannedDao.allOfInstanceOnce(targetInstanceId)
            olds.forEach { markDeleted(DeletedItem.TBL_PLANNED, it.id, t) }
            plannedDao.deleteForInstance(targetInstanceId)
            val fresh = src.map {
                PlannedItem(id = newId(), instanceId = targetInstanceId, content = it.content.trim(),
                    createdAt = t, updatedAt = t)
            }
            plannedDao.insertAll(fresh)
            n = fresh.size
        }
        return n
    }

    suspend fun updatePlanned(item: PlannedItem) {
        plannedDao.update(item.copy(updatedAt = now()))
    }

    /** 删除单条预选待测项，记墓碑随同步传播 */
    suspend fun deletePlanned(item: PlannedItem) {
        db.withTransaction {
            markDeleted(DeletedItem.TBL_PLANNED, item.id)
            plannedDao.delete(item)
        }
    }

    /**
     * 「开始测试」保存：每个测试项生成独立日志（不再合并），便于逐项管理。
     * 通过项：testContent=该项名称，result=PASS。
     * 未通过项：testContent=该项名称，result=FAIL，每条故障独立记录。
     * 复测通过的项若上次有未解决故障 → 自动标记已解决；
     * 测试人员必须来自调试员名单，绝不回落到登录账号；同时候选池沉淀。
     * @param failedItems 未通过项：预选项id to 故障现象列表（每项可有多条故障）
     * @return 最后一条日志id（兼容）
     */
    /**
     * 生成独立日志（永不合并）。
     * @param passIds 通过的测试项ID
     * @param failItems 新增故障: itemId -> [故障原因, ...]
     * @param resolvedFaults 已消除的故障: itemId -> [faultId, ...]（新创建故障可用symptom文本）
     * @param solutions 解决方法: faultId或symptom -> 文本（故障列表「通过」弹窗填写的）
     */
    suspend fun generateIndependentLogs(
        instanceId: String,
        passIds: List<String>,
        failItems: Map<String, List<String>>,
        resolvedFaults: Map<String, List<String>>,
        tester: String,
        actor: String,
        solutions: Map<String, String> = emptyMap()
    ) {
        val inst = instanceDao.getByIdOnce(instanceId)
            ?: throw IllegalArgumentException("柜子实例不存在")
        require(tester.isNotBlank()) { "请先绑定调试员" }
        val t = now()
        val allIds = (passIds + failItems.keys + resolvedFaults.keys).distinct()
        if (allIds.isEmpty()) return
        val items = plannedDao.byIdsOnce(allIds).filter { it.instanceId == instanceId }
        if (items.isEmpty()) return

        db.withTransaction {
            val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()

            for (item in items) {
                val itemId = item.id

                // 1. 新增故障 → 每条单独生成故障日志(logType=1)【必须先创建】
                val newlyCreatedFaults = mutableListOf<Pair<String, FaultRecord>>() // (symptom, record)
                val newFaults = failItems[itemId]
                if (!newFaults.isNullOrEmpty()) {
                    val faultIds = mutableListOf<String>()
                    for (symptom in newFaults) {
                        if (symptom.isBlank()) continue
                        val faultLog = DebugLog(
                            id = newId(), instanceId = instanceId, circuit = "",
                            logType = DebugLog.LOG_TYPE_FAULT,
                            testContent = item.content, tester = tester,
                            remark = symptom.trim(),
                            createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                        )
                        logDao.insert(faultLog)
                        val f = FaultRecord(
                            id = newId(), logId = faultLog.id, circuit = "",
                            symptom = symptom.trim(), solution = "",
                            occurredAt = t, resolvedAt = 0,
                            status = FaultRecord.STATUS_PENDING, updatedAt = t
                        )
                        faultDao.insert(f)
                        faultIds.add(f.id)
                        newlyCreatedFaults.add(symptom.trim() to f)
                    }
                    plannedDao.setResult(
                        listOf(itemId), PlannedItem.RESULT_FAIL, t, "",
                        faultIds.joinToString(",")
                    )
                    if (existing.add(item.content.trim())) {
                        candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = item.content.trim()))
                    }
                }

                // 2. 已消除故障 → 每条单独生成消除日志(logType=2)
                //    先尝试按faultId查找（DB中已有的故障），找不到则按symptom匹配新创建的故障
                val resolved = resolvedFaults[itemId]
                if (!resolved.isNullOrEmpty()) {
                    for (faultId in resolved) {
                        var fr = faultDao.byIdsOnce(listOf(faultId)).firstOrNull()
                        // 新创建的故障可能没有DB ID，按symptom匹配
                        if (fr == null) {
                            fr = newlyCreatedFaults.find { it.first == faultId }?.second
                        }
                        if (fr == null) continue
                        val resolutionLog = DebugLog(
                            id = newId(), instanceId = instanceId, circuit = "",
                            logType = DebugLog.LOG_TYPE_RESOLUTION,
                            testContent = item.content, tester = tester,
                            remark = fr.symptom,
                            createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                        )
                        logDao.insert(resolutionLog)
                        faultDao.passSingle(fr.id, t)
                        solutions[faultId]?.let { sol -> faultDao.setSolution(fr.id, sol, t) }
                    }
                    // 如果该项所有故障都已解决 → 设为通过
                    val remainingFaultIds = mutableListOf<String>()
                    remainingFaultIds.addAll(item.faultId.split(",").filter { it.isNotEmpty() })
                    remainingFaultIds.addAll(newlyCreatedFaults.map { it.second.id })
                    val remaining = faultDao.byIdsOnce(remainingFaultIds).count { it.status == FaultRecord.STATUS_PENDING }
                    if (remaining == 0) {
                        plannedDao.setResult(listOf(itemId), PlannedItem.RESULT_PASS, t, "", "")
                    }
                }

                // 3. 通过项 → 生成通过日志(logType=0)
                if (itemId in passIds) {
                    // 通过前自动解决旧故障，并为每条生成消除日志
                    if (item.faultId.isNotEmpty()) {
                        val prevFaultIds = item.faultId.split(",").filter { it.isNotEmpty() }
                        if (prevFaultIds.isNotEmpty()) {
                            val prevFaults = faultDao.byIdsOnce(prevFaultIds)
                            for (pf in prevFaults) {
                                if (pf.status == FaultRecord.STATUS_PENDING) {
                                    // 生成消除日志
                                    val resolutionLog = DebugLog(
                                        id = newId(), instanceId = instanceId, circuit = "",
                                        logType = DebugLog.LOG_TYPE_RESOLUTION,
                                        testContent = item.content, tester = tester,
                                        remark = pf.symptom,
                                        createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                                    )
                                    logDao.insert(resolutionLog)
                                }
                            }
                            faultDao.resolveByIds(prevFaultIds, t)
                        }
                    }
                    val passLog = DebugLog(
                        id = newId(), instanceId = instanceId, circuit = "",
                        logType = DebugLog.LOG_TYPE_PASS,
                        testContent = item.content, tester = tester,
                        remark = "",
                        createdBy = actor, updatedBy = actor, createdAt = t, updatedAt = t
                    )
                    logDao.insert(passLog)
                    plannedDao.setResult(listOf(itemId), PlannedItem.RESULT_PASS, t, passLog.id, "")
                    if (existing.add(item.content.trim())) {
                        candDao.insert(CandidateItem(id = newId(), typeId = inst.typeId, content = item.content.trim()))
                    }
                }
            }
        }
    }

    suspend fun faultsOf(logId: String) = faultDao.forLogOnce(logId)

    /** 获取某柜某测试项的所有故障记录（通过log.testContent匹配 + faultId直接查询） */
    suspend fun faultsForTestItem(instanceId: String, content: String, faultIdStr: String = ""): List<FaultRecord> {
        val byLog = faultDao.byInstanceAndContentOnce(instanceId, content).toMutableList()
        // 补充：直接通过faultId查找（消除日志删除后恢复场景）
        if (faultIdStr.isNotBlank()) {
            val ids = faultIdStr.split(",").filter { it.isNotEmpty() }
            if (ids.isNotEmpty()) {
                val byId = faultDao.byFaultIdsOnce(ids).map { it.id }.toSet()
                faultDao.byFaultIdsOnce(ids).forEach { f ->
                    if (f.id !in byLog.map { it.id }) byLog.add(f)
                }
            }
        }
        return byLog
    }

    /** 按id列表查询故障记录（TestChecklistActivity故障列表用） */
    suspend fun faultsByIds(ids: List<String>): List<FaultRecord> = faultDao.byIdsOnce(ids)

    /** 单条故障标记通过（复测通过时调用） */
    suspend fun passSingleFault(faultId: String) {
        faultDao.passSingle(faultId)
    }

    /** 单条故障驳回（恢复为待处理） */
    suspend fun unpassSingleFault(faultId: String) {
        faultDao.unpassSingle(faultId)
    }

    /** 更新某条故障的解决方法（时间线消除条目点击编辑） */
    suspend fun updateFaultSolution(faultId: String, solution: String) {
        faultDao.setSolution(faultId, solution.trim(), now())
    }

    /** 驳回已通过的测试项：删除通过日志 + 删除关联故障 + 重置PlannedItem为未测 */
    suspend fun rejectPassedItem(item: PlannedItem) {
        val t = now()
        val p = plannedDao.getByIdOnce(item.id) ?: return
        if (p.result != PlannedItem.RESULT_PASS) return
        val logId = p.logId
        db.withTransaction {
            if (logId.isNotEmpty()) {
                val log = logDao.getByIdOnce(logId)
                if (log != null) {
                    markDeleted(DeletedItem.TBL_LOGS, logId, t)
                    logDao.delete(log)
                }
                val faults = faultDao.forLogOnce(logId)
                for (f in faults) {
                    markDeleted(DeletedItem.TBL_FAULTS, f.id, t)
                }
                faultDao.deleteForLog(logId)
            }
            plannedDao.setResult(listOf(p.id), PlannedItem.RESULT_UNTESTED, 0L, "", "")
        }
    }

    /**
     * 获取某测试项的历史流水（故障日志→消除日志→通过日志），按时间排序。
     * 用于已通过项点击时的时间线对话框，以及日志列表点击时的时间线。
     */
    suspend fun historyTimeline(instanceId: String, itemContent: String): List<Triple<DebugLog, String, List<FaultRecord>>> {
        val logs = logDao.byInstanceAndContentOnce(instanceId, itemContent)
        return logs.map { log ->
            val faults = when (log.logType) {
                DebugLog.LOG_TYPE_FAULT -> faultDao.forLogOnce(log.id)
                // 消除日志：带回关联的已解决故障（含解决方法，供时间线显示与编辑）
                DebugLog.LOG_TYPE_RESOLUTION -> {
                    val hit = faultDao.byInstanceAndContentOnce(instanceId, itemContent)
                        .filter { it.status == FaultRecord.STATUS_RESOLVED && it.symptom == log.remark }
                    val latest = hit.maxByOrNull { it.resolvedAt }
                    if (latest == null) emptyList() else listOf(latest)
                }
                else -> emptyList()
            }
            Triple(log, log.remark, faults)
        }
    }

    // ---------- 测试员账号 ----------

    suspend fun testerAccounts(): List<TesterAccount> = db.testerAccountDao().allOnce()

    /** 注册/刷新测试员，返回是否新注册 */
    suspend fun registerTester(username: String, source: String): Boolean {
        val dao = db.testerAccountDao()
        val existed = dao.byUsername(username) != null
        if (!existed) dao.insert(TesterAccount(id = newId(), username = username, source = source))
        else dao.updateSource(username, source)
        return !existed
    }

    // ---------- 调试员名单 ----------

    suspend fun debuggers(): List<Debugger> = debuggerDao.allOnce()

    /** 新增调试员；名字为空或已存在返回false */
    suspend fun addDebugger(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (debuggerDao.byNameOnce(n) != null) return false
        debuggerDao.insert(Debugger(id = newId(), name = n))
        return true
    }

    /**
     * 改名。历史日志一律不动（用户约定：名单操作不影响已有数据）。
     * 目标名已存在返回false。
     */
    suspend fun renameDebugger(id: String, newName: String): Boolean {
        val n = newName.trim()
        if (n.isEmpty()) return false
        val hit = debuggerDao.byIdOnce(id) ?: return false
        if (n == hit.name) return true
        if (debuggerDao.byNameOnce(n) != null) return false
        debuggerDao.updateAll(listOf(hit.copy(name = n, updatedAt = now())))
        return true
    }

    /** 删除调试员（历史日志保留原姓名），记墓碑随同步传播 */
    suspend fun deleteDebugger(id: String) {
        debuggerDao.byIdOnce(id)?.let {
            db.withTransaction {
                markDeleted(DeletedItem.TBL_DEBUGGERS, it.id)
                debuggerDao.delete(it)
            }
        }
    }

    // ---------- 统计 / 导出 / 备份 ----------

    suspend fun stats(): Stats = Stats(
        projects = projectDao.count(),
        types = typeDao.count(),
        instances = instanceDao.count(),
        logs = logDao.count(),
        pendingFaults = faultDao.countPending()
    )

    suspend fun collectExport(filter: ExportFilter = ExportFilter()): Pair<List<LogListItem>, List<FaultExportRow>> {
        var (logs, faults) = logDao.exportAll() to faultDao.exportAll()
        // 状态筛选
        if (filter.status != null) {
            when (filter.status) {
                0 -> { // 含故障
                    val logIds = faults.map { it.fault.logId }.toSet()
                    logs = logs.filter { it.log.id in logIds }
                }
                1 -> { // 仅通过（logType == PASS）
                    logs = logs.filter { it.log.logType == DebugLog.LOG_TYPE_PASS }
                }
            }
        }
        // 测试人员筛选
        if (filter.testers.isNotEmpty()) {
            logs = logs.filter { it.log.tester in filter.testers }
        }
        // 柜子类型筛选
        if (filter.typeIds.isNotEmpty()) {
            logs = logs.filter { it.typeId in filter.typeIds }
        }
        // 日期范围筛选
        if (filter.dateFrom > 0) logs = logs.filter { it.log.createdAt >= filter.dateFrom }
        if (filter.dateTo > 0) logs = logs.filter { it.log.createdAt <= filter.dateTo }
        // 故障表跟随日志筛选结果
        val logIds = logs.map { it.log.id }.toSet()
        faults = faults.filter { it.fault.logId in logIds }
        return logs to faults
    }

    /**
     * 范围导出：按项目（全部柜子）或单个柜子过滤日志与故障。
     * 两个参数都为空 = 全量导出。用于项目卡/柜子长按菜单的定向导出。
     * @param filter 可选筛选条件（状态/人员/日期/列选择）
     */
    suspend fun collectExportOf(
        projectId: String = "",
        instanceId: String = "",
        filter: ExportFilter = ExportFilter()
    ):
        Pair<List<LogListItem>, List<FaultExportRow>> {
        // 先应用筛选条件
        val (filteredLogs, filteredFaults) = collectExport(filter)
        if (projectId.isBlank() && instanceId.isBlank()) return filteredLogs to filteredFaults
        // LogListItem 无 projectId 字段，经实例归属换算
        val instIds = if (projectId.isNotBlank())
            instanceDao.byProjectOnce(projectId).map { it.id }.toSet() else null
        val ls = filteredLogs.filter {
            (instIds == null || it.log.instanceId in instIds) &&
                (instanceId.isBlank() || it.log.instanceId == instanceId)
        }
        val logIds = ls.map { it.log.id }.toSet()
        val fs = filteredFaults.filter { it.fault.logId in logIds }
        return ls to fs
    }

    companion object {
        const val BACKUP_APP_TAG = "power-debug-log"
        const val BACKUP_SCHEMA = 9
    }

    /**
     * 备份为 JSON 字符串。该格式同时是后期 PC/网页端的官方数据交换格式：
     * 字段名即数据库列名，schemaVersion 变更时需提供迁移说明。
     * schemaVersion 2：全表UUID主键+updatedAt合并时钟；日志含创建/修改账号。
     * schemaVersion 3：新增 plannedItems（柜子实例的预选待测清单）。
     * schemaVersion 4：plannedItems 增加三态结果 result 与关联故障 faultId。
     * schemaVersion 5：新增 debuggers（调试员名单）。
     * schemaVersion 6：新增 deletedItems（删除墓碑，删除操作随同步传播）。
     * schemaVersion 7：CabinetInstance 新增 shortName（精简名，网格视图显示用）。
     * schemaVersion 8：CabinetInstance 新增 sortOrder（拖动排序用，0=默认按名称）。
     * schemaVersion 9：CabinetInstance 新增 rowGroup（行分组编号，0=未分组）。
     */
    suspend fun backupJson(): String {
        val jo = JSONObject()
        jo.put("app", BACKUP_APP_TAG)
        jo.put("schemaVersion", BACKUP_SCHEMA)
        jo.put("exportedAt", now())

        fun arr(list: List<JSONObject>): JSONArray = JSONArray().apply { list.forEach(::put) }

        jo.put(
            "projects",
            arr(projectDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("code", it.code)
                    .put("remark", it.remark)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "cabinetTypes",
            arr(typeDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("remark", it.remark)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "candidateItems",
            arr(candDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("typeId", it.typeId).put("content", it.content)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "instances",
            arr(instanceDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("projectId", it.projectId).put("typeId", it.typeId)
                    .put("name", it.name).put("deviceCode", it.deviceCode)
                    .put("location", it.location).put("installer", it.installer)
                    .put("shortName", it.shortName)
                    .put("sortOrder", it.sortOrder)
                    .put("rowGroup", it.rowGroup)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "logs",
            arr(logDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("instanceId", it.instanceId)
                    .put("circuit", it.circuit).put("testContent", it.testContent)
                    .put("tester", it.tester).put("remark", it.remark)
                    .put("createdBy", it.createdBy).put("updatedBy", it.updatedBy)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "faults",
            arr(faultDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("logId", it.logId).put("circuit", it.circuit)
                    .put("symptom", it.symptom).put("solution", it.solution)
                    .put("occurredAt", it.occurredAt).put("resolvedAt", it.resolvedAt)
                    .put("status", it.status).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "plannedItems",
            arr(plannedDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("instanceId", it.instanceId).put("content", it.content)
                    .put("enabled", if (it.enabled) 1 else 0)
                    .put("doneAt", it.doneAt).put("logId", it.logId)
                    .put("result", it.result).put("faultId", it.faultId)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        jo.put(
            "debuggers",
            arr(debuggerDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            })
        )
        // v6：删除墓碑（记录"什么删过了"，合并端据此删除本地对应行）
        jo.put(
            "deletedItems",
            arr(tombDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("tbl", it.tbl).put("itemId", it.itemId)
                    .put("deletedAt", it.deletedAt)
            })
        )
        return jo.toString(2)
    }

    /**
     * 解析备份JSON，统一转换为本机实体。
     * 支持 schemaVersion 2（直接使用）；也支持 1（旧版int主键备份：生成新UUID并重映射全部引用，
     * 缺失的合并时钟字段以 createdAt 兜底）。
     */
    private fun parseBackup(text: String): ParsedBackup {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..BACKUP_SCHEMA) { "不支持的备份版本：$version" }
        val pb = ParsedBackup()

        if (version >= 2) {
            root.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.projects += Project(
                        id = getString("id"), name = getString("name"),
                        code = optString("code"), remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("cabinetTypes")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.types += CabinetType(
                        id = getString("id"), name = getString("name"),
                        remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("candidateItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.cands += CandidateItem(
                        id = getString("id"), typeId = getString("typeId"),
                        content = getString("content"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("instances")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.instances += CabinetInstance(
                        id = getString("id"), projectId = getString("projectId"),
                        typeId = getString("typeId"), name = getString("name"),
                        deviceCode = optString("deviceCode"), location = optString("location"),
                        installer = optString("installer"),
                        shortName = optString("shortName"),
                        sortOrder = optInt("sortOrder"),
                        rowGroup = optInt("rowGroup"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("logs")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.logs += DebugLog(
                        id = getString("id"), instanceId = getString("instanceId"),
                        circuit = optString("circuit"), testContent = getString("testContent"),
                        tester = optString("tester"), remark = optString("remark"),
                        createdBy = optString("createdBy"), updatedBy = optString("updatedBy"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("faults")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.faults += FaultRecord(
                        id = getString("id"), logId = getString("logId"),
                        circuit = optString("circuit"), symptom = optString("symptom"),
                        solution = optString("solution"), occurredAt = optLong("occurredAt"),
                        resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                        updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("plannedItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.planned += PlannedItem(
                        id = getString("id"), instanceId = getString("instanceId"),
                        content = getString("content"),
                        enabled = optInt("enabled", 1) != 0,
                        doneAt = optLong("doneAt"), logId = optString("logId"),
                        result = optInt("result", PlannedItem.RESULT_UNTESTED),
                        faultId = optString("faultId"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            // v5起新增；旧版备份（v2~v4）无此数组，静默跳过
            root.optJSONArray("debuggers")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    pb.debuggers += Debugger(
                        id = getString("id"), name = getString("name"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            // v6起新增：删除墓碑；旧版备份无此数组静默跳过
            root.optJSONArray("deletedItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    val tbl = optString("tbl")
                    val itemId = optString("itemId")
                    if (tbl.isNotEmpty() && itemId.isNotEmpty()) pb.tombs += DeletedItem(
                        id = optString("id").ifBlank { newId() },
                        tbl = tbl, itemId = itemId, deletedAt = optLong("deletedAt")
                    )
                }
            }
            return pb
        }

        // ---- v1 旧格式：int主键 → UUID 重映射 ----
        val mapP = HashMap<Long, String>()
        val mapT = HashMap<Long, String>()
        val mapC = HashMap<Long, String>()
        val mapI = HashMap<Long, String>()
        val mapL = HashMap<Long, String>()
        val mapF = HashMap<Long, String>()

        fun idOf(map: MutableMap<Long, String>, old: Long): String =
            map.getOrPut(old) { newId() }

        root.optJSONArray("projects")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.projects += Project(
                    id = idOf(mapP, old), name = getString("name"),
                    code = optString("code"), remark = optString("remark"),
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("cabinetTypes")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.types += CabinetType(
                    id = idOf(mapT, old), name = getString("name"),
                    remark = optString("remark"), createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("candidateItems")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.cands += CandidateItem(
                    id = idOf(mapC, old), typeId = idOf(mapT, getLong("typeId")),
                    content = getString("content"), createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("instances")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.instances += CabinetInstance(
                    id = idOf(mapI, old),
                    projectId = idOf(mapP, getLong("projectId")),
                    typeId = idOf(mapT, getLong("typeId")),
                    name = getString("name"),
                    deviceCode = optString("deviceCode"), location = optString("location"),
                    installer = optString("installer"),
                    shortName = optString("shortName"),
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("logs")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("createdAt")
                pb.logs += DebugLog(
                    id = idOf(mapL, old),
                    instanceId = idOf(mapI, getLong("instanceId")),
                    circuit = optString("circuit"), testContent = getString("testContent"),
                    tester = optString("tester"), remark = optString("remark"),
                    createdBy = "", updatedBy = "",
                    createdAt = t, updatedAt = optLong("updatedAt", t)
                )
            }
        }
        root.optJSONArray("faults")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                val old = getLong("id"); val t = optLong("occurredAt")
                pb.faults += FaultRecord(
                    id = idOf(mapF, old),
                    logId = idOf(mapL, getLong("logId")),
                    circuit = optString("circuit"), symptom = optString("symptom"),
                    solution = optString("solution"), occurredAt = t,
                    resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                    updatedAt = optLong("updatedAt", t)
                )
            }
        }
        return pb
    }

    /**
     * 从 JSON 恢复（整体覆盖）。解析失败会抛异常且不改动现有数据。
     * 支持 v1/v2 备份文件。
     */
    suspend fun restoreJson(text: String): Stats {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }
        val version = root.optInt("schemaVersion", 1)
        require(version in 1..BACKUP_SCHEMA) { "不支持的备份版本：$version" }

        val projects = mutableListOf<Project>()
        val types = mutableListOf<CabinetType>()
        val cands = mutableListOf<CandidateItem>()
        val instances = mutableListOf<CabinetInstance>()
        val logs = mutableListOf<DebugLog>()
        val faults = mutableListOf<FaultRecord>()
        val planned = mutableListOf<PlannedItem>()
        val debuggers = mutableListOf<Debugger>()
        val tombs = mutableListOf<DeletedItem>()

        if (version >= 2) {
            root.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    projects += Project(
                        id = getString("id"), name = getString("name"),
                        code = optString("code"), remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("cabinetTypes")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    types += CabinetType(
                        id = getString("id"), name = getString("name"),
                        remark = optString("remark"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("candidateItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    cands += CandidateItem(
                        id = getString("id"), typeId = getString("typeId"),
                        content = getString("content"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("instances")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    instances += CabinetInstance(
                        id = getString("id"), projectId = getString("projectId"),
                        typeId = getString("typeId"), name = getString("name"),
                        deviceCode = optString("deviceCode"), location = optString("location"),
                        installer = optString("installer"),
                        shortName = optString("shortName"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("logs")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    logs += DebugLog(
                        id = getString("id"), instanceId = getString("instanceId"),
                        circuit = optString("circuit"), testContent = getString("testContent"),
                        tester = optString("tester"), remark = optString("remark"),
                        createdBy = optString("createdBy"), updatedBy = optString("updatedBy"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("faults")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    faults += FaultRecord(
                        id = getString("id"), logId = getString("logId"),
                        circuit = optString("circuit"), symptom = optString("symptom"),
                        solution = optString("solution"), occurredAt = optLong("occurredAt"),
                        resolvedAt = optLong("resolvedAt"), status = optInt("status"),
                        updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("plannedItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    planned += PlannedItem(
                        id = getString("id"), instanceId = getString("instanceId"),
                        content = getString("content"),
                        enabled = optInt("enabled", 1) != 0,
                        doneAt = optLong("doneAt"), logId = optString("logId"),
                        result = optInt("result", PlannedItem.RESULT_UNTESTED),
                        faultId = optString("faultId"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("debuggers")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    debuggers += Debugger(
                        id = getString("id"), name = getString("name"),
                        createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                    )
                }
            }
            root.optJSONArray("deletedItems")?.let { a ->
                for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                    val tbl = optString("tbl")
                    val itemId = optString("itemId")
                    if (tbl.isNotEmpty() && itemId.isNotEmpty()) tombs += DeletedItem(
                        id = optString("id").ifBlank { newId() },
                        tbl = tbl, itemId = itemId, deletedAt = optLong("deletedAt")
                    )
                }
            }
        } else {
            // v1：解析后走同一套uuid重映射（复用parse逻辑）
            val pb = parseBackup(text)
            projects += pb.projects; types += pb.types; cands += pb.cands
            instances += pb.instances; logs += pb.logs; faults += pb.faults
            planned += pb.planned
        }

        db.withTransaction {
            faultDao.wipe(); logDao.wipe(); instanceDao.wipe()
            candDao.wipe(); typeDao.wipe(); projectDao.wipe()
            plannedDao.wipe()
            debuggerDao.wipe()
            tombDao.wipe()
            projectDao.insertAll(projects)
            typeDao.insertAll(types)
            candDao.insertAll(cands)
            instanceDao.insertAll(instances)
            logDao.insertAll(logs)
            faultDao.upsertAll(faults)
            if (planned.isNotEmpty()) plannedDao.insertAll(planned)
            if (debuggers.isNotEmpty()) debuggerDao.insertAll(debuggers)
            if (tombs.isNotEmpty()) tombDao.insertAll(tombs)
        }
        return Stats(projects.size, types.size, instances.size, logs.size, faults.count { it.status == FaultRecord.STATUS_PENDING })
    }

    /**
     * 智能合并远端数据到本机：
     * 1) 墓碑先行——远端删除记录落库并应用删除（经DAO删除，级联与本机一致）；
     * 2) 数据按id去重、同id冲突updatedAt新者胜；已删id与父链已断的孤儿行一律跳过，
     *    防止被删记录借旧快照复活。
     */
    suspend fun mergeJson(text: String): MergeResult = applyMerge(parseBackup(text))

    /**
     * 合并预览（只读不写库）：返回与真实合并一致的统计结果。
     */
    suspend fun mergePreview(text: String): MergeResult {
        val pb = parseBackup(text)
        // 墓碑全集 = 本机已有 ∪ 快照带来的
        val tombs = HashMap<String, HashSet<String>>()
        tombDao.allOnce().forEach { tombs.getOrPut(it.tbl) { HashSet() }.add(it.itemId) }
        pb.tombs.forEach { tombs.getOrPut(it.tbl) { HashSet() }.add(it.itemId) }
        fun dead(tbl: String, id: String) = tombs[tbl]?.contains(id) == true

        // 预计本机会被墓碑删掉的行数（各表直接命中数；级联另计）
        var approxDel = 0
        approxDel += projectDao.allOnce().count { dead(DeletedItem.TBL_PROJECTS, it.id) }
        approxDel += typeDao.allOnce().count { dead(DeletedItem.TBL_TYPES, it.id) }
        approxDel += candDao.allOnce().count { dead(DeletedItem.TBL_CANDS, it.id) }
        approxDel += instanceDao.allOnce().count { dead(DeletedItem.TBL_INSTANCES, it.id) }
        approxDel += logDao.allOnce().count { dead(DeletedItem.TBL_LOGS, it.id) }
        approxDel += faultDao.allOnce().count { dead(DeletedItem.TBL_FAULTS, it.id) }
        approxDel += plannedDao.allOnce().count { dead(DeletedItem.TBL_PLANNED, it.id) }
        approxDel += debuggerDao.allOnce().count { dead(DeletedItem.TBL_DEBUGGERS, it.id) }

        val lp = projectDao.allOnce().associateBy { it.id }
        val lt = typeDao.allOnce().associateBy { it.id }
        val lc = candDao.allOnce()
        val li = instanceDao.allOnce().associateBy { it.id }
        val ll = logDao.allOnce().associateBy { it.id }
        val lf = faultDao.allOnce().associateBy { it.id }

        fun newer(map: Map<String, Long>, id: String, ts: Long) =
            map[id]?.let { it < ts } == true

        val lcById = lc.associateBy { it.id }
        val lcPair = lc.map { it.typeId to it.content }.toHashSet()
        val lpl = plannedDao.allOnce()
        val lplById = lpl.associateBy { it.id }
        val lplPair = lpl.map { it.instanceId to it.content }.toHashSet()
        val ld = debuggerDao.allOnce()
        val ldById = ld.associateBy { it.id }
        val ldNames = ld.map { it.name }.toHashSet()

        // 存活父集合 = 本机现存 ∪ 快照中未被删的
        val aliveP = buildSet {
            addAll(lp.keys); pb.projects.forEach { if (!dead(DeletedItem.TBL_PROJECTS, it.id)) add(it.id) }
        }
        val aliveT = buildSet {
            addAll(lt.keys); pb.types.forEach { if (!dead(DeletedItem.TBL_TYPES, it.id)) add(it.id) }
        }
        val aliveI = buildSet {
            addAll(li.keys)
            pb.instances.filter { it.projectId in aliveP && it.typeId in aliveT && !dead(DeletedItem.TBL_INSTANCES, it.id) }
                .forEach { add(it.id) }
        }
        val aliveL = buildSet {
            addAll(ll.keys)
            pb.logs.filter { it.instanceId in aliveI && !dead(DeletedItem.TBL_LOGS, it.id) }.forEach { add(it.id) }
        }

        return MergeResult(
            newProjects = pb.projects.count { it.id !in lp && !dead(DeletedItem.TBL_PROJECTS, it.id) },
            updProjects = pb.projects.count { !dead(DeletedItem.TBL_PROJECTS, it.id) && newer(lp.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newTypes = pb.types.count { it.id !in lt && !dead(DeletedItem.TBL_TYPES, it.id) },
            updTypes = pb.types.count { !dead(DeletedItem.TBL_TYPES, it.id) && newer(lt.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newCands = pb.cands.count {
                it.typeId in aliveT && it.id !in lcById &&
                    (it.typeId to it.content) !in lcPair && !dead(DeletedItem.TBL_CANDS, it.id)
            },
            newInstances = pb.instances.count {
                it.id !in li && it.projectId in aliveP && it.typeId in aliveT && !dead(DeletedItem.TBL_INSTANCES, it.id)
            },
            updInstances = pb.instances.count { !dead(DeletedItem.TBL_INSTANCES, it.id) && newer(li.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newLogs = pb.logs.count { it.id !in ll && it.instanceId in aliveI && !dead(DeletedItem.TBL_LOGS, it.id) },
            updLogs = pb.logs.count { !dead(DeletedItem.TBL_LOGS, it.id) && newer(ll.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newFaults = pb.faults.count {
                it.id !in lf && (it.logId.isBlank() || it.logId in aliveL) && !dead(DeletedItem.TBL_FAULTS, it.id)
            },
            updFaults = pb.faults.count { !dead(DeletedItem.TBL_FAULTS, it.id) && newer(lf.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newPlanned = pb.planned.count {
                it.instanceId in aliveI && it.id !in lplById &&
                    (it.instanceId to it.content) !in lplPair && !dead(DeletedItem.TBL_PLANNED, it.id)
            },
            updPlanned = pb.planned.count { !dead(DeletedItem.TBL_PLANNED, it.id) && newer(lplById.mapValues { it.value.updatedAt }, it.id, it.updatedAt) },
            newDebuggers = pb.debuggers.count {
                it.id !in ldById && it.name !in ldNames && !dead(DeletedItem.TBL_DEBUGGERS, it.id)
            },
            updDebuggers = pb.debuggers.count { d ->
                if (dead(DeletedItem.TBL_DEBUGGERS, d.id)) return@count false
                val local = ldById[d.id] ?: return@count false
                if (d.updatedAt <= local.updatedAt) return@count false
                // 改名目标与其他本地行重名时无法安全更新（唯一索引），跳过
                ld.none { it.id != d.id && it.name == d.name }
            },
            appliedTombs = approxDel
        )
    }

    private suspend fun applyMerge(pb: ParsedBackup): MergeResult {
        val r = MergeResult()
        db.withTransaction {
            // ---------- 0) 墓碑先行：远端墓碑落库（IGNORE去重）→ 统一应用删除 ----------
            if (pb.tombs.isNotEmpty()) tombDao.insertAll(pb.tombs)
            val tombs = HashMap<String, HashSet<String>>()
            tombDao.allOnce().forEach { tombs.getOrPut(it.tbl) { HashSet() }.add(it.itemId) }
            fun dead(tbl: String, id: String) = tombs[tbl]?.contains(id) == true

            // 经DAO删除（非裸SQL），外键级联与本机直接删除完全一致，各设备最终状态收敛
            var applied = 0
            projectDao.allOnce().filter { dead(DeletedItem.TBL_PROJECTS, it.id) }.let {
                if (it.isNotEmpty()) { projectDao.deleteAll(it); applied += it.size }
            }
            typeDao.allOnce().filter { dead(DeletedItem.TBL_TYPES, it.id) }.let {
                if (it.isNotEmpty()) { typeDao.deleteAll(it); applied += it.size }
            }
            candDao.allOnce().filter { dead(DeletedItem.TBL_CANDS, it.id) }.let {
                if (it.isNotEmpty()) { candDao.deleteAll(it); applied += it.size }
            }
            instanceDao.allOnce().filter { dead(DeletedItem.TBL_INSTANCES, it.id) }.let {
                if (it.isNotEmpty()) { instanceDao.deleteAll(it); applied += it.size }
            }
            logDao.allOnce().filter { dead(DeletedItem.TBL_LOGS, it.id) }.let {
                if (it.isNotEmpty()) { logDao.deleteAll(it); applied += it.size }
            }
            faultDao.allOnce().filter { dead(DeletedItem.TBL_FAULTS, it.id) }.let {
                if (it.isNotEmpty()) { faultDao.deleteAll(it); applied += it.size }
            }
            plannedDao.allOnce().filter { dead(DeletedItem.TBL_PLANNED, it.id) }.let {
                if (it.isNotEmpty()) { plannedDao.deleteAll(it); applied += it.size }
            }
            debuggerDao.allOnce().filter { dead(DeletedItem.TBL_DEBUGGERS, it.id) }.let {
                if (it.isNotEmpty()) { debuggerDao.deleteAll(it); applied += it.size }
            }
            r.appliedTombs = applied

            // ---------- 1) 数据合并：跳过已删id与父链已断的孤儿行，防借旧快照复活 ----------
            // 父表在前，保证外键引用顺序；UPDATE不触发级联，父行更新安全
            val lp = projectDao.allOnce().associateBy { it.id }
            val insP = pb.projects.filter { it.id !in lp && !dead(DeletedItem.TBL_PROJECTS, it.id) }
            val updP = pb.projects.filter { !dead(DeletedItem.TBL_PROJECTS, it.id) && lp[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            projectDao.insertAll(insP); projectDao.updateAll(updP)
            r.newProjects = insP.size; r.updProjects = updP.size
            val aliveP = lp.keys + insP.map { it.id }

            val lt = typeDao.allOnce().associateBy { it.id }
            val insT = pb.types.filter { it.id !in lt && !dead(DeletedItem.TBL_TYPES, it.id) }
            val updT = pb.types.filter { !dead(DeletedItem.TBL_TYPES, it.id) && lt[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            typeDao.insertAll(insT); typeDao.updateAll(updT)
            r.newTypes = insT.size; r.updTypes = updT.size
            val aliveT = lt.keys + insT.map { it.id }

            val lc = candDao.allOnce()
            val lcById = lc.associateBy { it.id }
            val lcPair = lc.map { it.typeId to it.content }.toHashSet()
            val insC = pb.cands.filter {
                it.typeId in aliveT && it.id !in lcById &&
                    (it.typeId to it.content) !in lcPair && !dead(DeletedItem.TBL_CANDS, it.id)
            }
            val updC = pb.cands.filter { !dead(DeletedItem.TBL_CANDS, it.id) && lcById[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            candDao.insertAll(insC); candDao.insertAll(updC) // IGNORE策略：内容重复时静默跳过
            r.newCands = insC.size

            val li = instanceDao.allOnce().associateBy { it.id }
            val insI = pb.instances.filter {
                it.id !in li && it.projectId in aliveP && it.typeId in aliveT && !dead(DeletedItem.TBL_INSTANCES, it.id)
            }
            val updI = pb.instances.filter { !dead(DeletedItem.TBL_INSTANCES, it.id) && li[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            instanceDao.insertAll(insI); instanceDao.updateAll(updI)
            r.newInstances = insI.size; r.updInstances = updI.size
            val aliveI = li.keys + insI.map { it.id }

            val ll = logDao.allOnce().associateBy { it.id }
            val insL = pb.logs.filter { it.id !in ll && it.instanceId in aliveI && !dead(DeletedItem.TBL_LOGS, it.id) }
            val updL = pb.logs.filter { !dead(DeletedItem.TBL_LOGS, it.id) && ll[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            logDao.insertAll(insL); logDao.updateAll(updL)
            r.newLogs = insL.size; r.updLogs = updL.size
            val aliveL = ll.keys + insL.map { it.id }

            val lf = faultDao.allOnce().associateBy { it.id }
            val insF = pb.faults.filter {
                it.id !in lf && (it.logId.isBlank() || it.logId in aliveL) && !dead(DeletedItem.TBL_FAULTS, it.id)
            }
            val updF = pb.faults.filter { !dead(DeletedItem.TBL_FAULTS, it.id) && lf[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            faultDao.upsertAll(insF); faultDao.updateAll(updF)
            r.newFaults = insF.size; r.updFaults = updF.size

            // 预选待测：同id新者胜；(柜子,内容) 相同但id不同视为同一项，IGNORE静默跳过
            val lpl = plannedDao.allOnce()
            val lplById = lpl.associateBy { it.id }
            val lplPair = lpl.map { it.instanceId to it.content }.toHashSet()
            val insPl = pb.planned.filter {
                it.instanceId in aliveI && it.id !in lplById &&
                    (it.instanceId to it.content) !in lplPair && !dead(DeletedItem.TBL_PLANNED, it.id)
            }
            val updPl = pb.planned.filter { !dead(DeletedItem.TBL_PLANNED, it.id) && lplById[it.id]?.let { l -> it.updatedAt > l.updatedAt } == true }
            plannedDao.insertAll(insPl); plannedDao.updateAll(updPl)
            r.newPlanned = insPl.size; r.updPlanned = updPl.size

            // 调试员名单：同id新者胜；按name去重（不同id同名只保留先到者）；
            // 远端改名撞上本地已有姓名时跳过该行，避免唯一索引冲突
            val ld = debuggerDao.allOnce()
            val ldById = ld.associateBy { it.id }
            val ldNames = ld.map { it.name }.toHashSet()
            val insD = pb.debuggers.filter { it.id !in ldById && it.name !in ldNames && !dead(DeletedItem.TBL_DEBUGGERS, it.id) }
            val updD = pb.debuggers.filter { d ->
                if (dead(DeletedItem.TBL_DEBUGGERS, d.id)) return@filter false
                val local = ldById[d.id] ?: return@filter false
                if (d.updatedAt <= local.updatedAt) return@filter false
                ld.none { it.id != d.id && it.name == d.name }
            }
            debuggerDao.insertAll(insD); debuggerDao.updateAll(updD)
            r.newDebuggers = insD.size; r.updDebuggers = updD.size
        }
        return r
    }
}
