package com.fieldlog.powerdebug.data

import androidx.room.withTransaction
import com.fieldlog.powerdebug.data.db.AppDatabase
import com.fieldlog.powerdebug.data.db.CabinetInstance
import com.fieldlog.powerdebug.data.db.CabinetType
import com.fieldlog.powerdebug.data.db.CandidateItem
import com.fieldlog.powerdebug.data.db.DebugLog
import com.fieldlog.powerdebug.data.db.FaultExportRow
import com.fieldlog.powerdebug.data.db.FaultRecord
import com.fieldlog.powerdebug.data.db.LogListItem
import com.fieldlog.powerdebug.data.db.Project
import com.fieldlog.powerdebug.data.db.ProjectListItem
import com.fieldlog.powerdebug.data.db.TypeListItem
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** 数据统计（工具页展示） */
data class Stats(
    val projects: Int,
    val types: Int,
    val instances: Int,
    val logs: Int,
    val pendingFaults: Int
)

/**
 * 业务逻辑统一入口。
 * 后期电脑端/网页端移植时，按同样的语义实现本层即可复用全部业务规则
 * （候选池自动沉淀、级联删除、JSON 备份格式等）。
 */
class Repository(private val db: AppDatabase) {

    private val projectDao = db.projectDao()
    private val typeDao = db.cabinetTypeDao()
    private val candDao = db.candidateItemDao()
    private val instanceDao = db.instanceDao()
    private val logDao = db.debugLogDao()
    private val faultDao = db.faultRecordDao()

    // ---------- 观察 ----------

    fun watchProjects(): Flow<List<Project>> = projectDao.watchAllAsFlow()
    fun watchProjectItems(): Flow<List<ProjectListItem>> = projectDao.watchListItemsAsFlow()
    fun watchTypeItems(): Flow<List<TypeListItem>> = typeDao.watchListItemsAsFlow()
    fun watchTypes(): Flow<List<CabinetType>> = typeDao.watchAllAsFlow()
    fun watchInstancesOf(projectId: Long): Flow<List<CabinetInstance>> =
        instanceDao.watchByProjectAsFlow(projectId)
    fun watchPool(typeId: Long): Flow<List<CandidateItem>> = candDao.watchByTypeAsFlow(typeId)

    // ---------- 项目 ----------

    suspend fun getProject(id: Long): Project? = projectDao.getByIdOnce(id)

    suspend fun saveProject(p: Project): Long =
        if (p.id == 0L) projectDao.insert(p) else { projectDao.update(p); p.id }

    /** 返回受影响柜子数（用于确认弹窗），-1 表示项目不存在 */
    suspend fun deleteProject(id: Long): Int {
        val p = projectDao.getByIdOnce(id) ?: return -1
        val cabinets = instanceDao.byProjectOnce(id).size
        projectDao.delete(p)
        return cabinets
    }

    // ---------- 柜子类型与候选池 ----------

    suspend fun getType(id: Long): CabinetType? = typeDao.getByIdOnce(id)
    suspend fun allTypes(): List<CabinetType> = typeDao.allOnce()

    suspend fun saveType(t: CabinetType): Long =
        if (t.id == 0L) typeDao.insert(t) else { typeDao.update(t); t.id }

    /** 返回使用该类型的实例数（用于确认弹窗），-1 表示类型不存在 */
    suspend fun deleteType(id: Long): Int {
        val t = typeDao.getByIdOnce(id) ?: return -1
        val usage = instanceDao.byTypeWithProject(id).size
        typeDao.delete(t)
        return usage
    }

    /**
     * 向候选池追加条目：自动按整行去重。
     * @return 新增条数
     */
    suspend fun addCandidatesFromText(typeId: Long, text: String): Int {
        val existing = candDao.contentsOnce(typeId).map { it.trim() }.toHashSet()
        var added = 0
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it) }
            .forEach {
                candDao.insert(CandidateItem(typeId = typeId, content = it))
                added++
            }
        return added
    }

    suspend fun deleteCandidate(item: CandidateItem) = candDao.delete(item)

    suspend fun instanceUsageOfType(typeId: Long) = instanceDao.byTypeWithProject(typeId)

    // ---------- 柜子实例 ----------

    suspend fun getInstance(id: Long): CabinetInstance? = instanceDao.getByIdOnce(id)
    suspend fun instancesOfProjectOnce(projectId: Long) = instanceDao.byProjectOnce(projectId)

    suspend fun saveInstance(i: CabinetInstance): Long =
        if (i.id == 0L) instanceDao.insert(i) else { instanceDao.update(i); i.id }

    /** 返回该柜子的日志条数（用于确认弹窗），-1 表示不存在 */
    suspend fun deleteInstance(id: Long): Int {
        val inst = instanceDao.getByIdOnce(id) ?: return -1
        val logs = logDao.countLogsOf(inst.id)
        instanceDao.delete(inst)
        return logs
    }

    // ---------- 调试日志 ----------

    suspend fun searchLogs(
        projectId: Long, typeId: Long, instanceId: Long,
        status: Int, circuit: String, q: String
    ): List<LogListItem> = logDao.search(projectId, typeId, instanceId, status, circuit.trim(), q.trim())

    suspend fun getLogDetail(id: Long): LogListItem? = logDao.getDetailOnce(id)

    suspend fun distinctCircuits(projectId: Long, typeId: Long) =
        logDao.distinctCircuits(projectId, typeId)

    /**
     * 保存日志（新建或编辑）并同步故障记录；
     * 同时把测试内容中出现的新行自动沉淀进对应柜子类型的候选池。
     */
    suspend fun saveLog(log: DebugLog, faults: List<FaultRecord>) {
        val inst = instanceDao.getByIdOnce(log.instanceId)
            ?: throw IllegalArgumentException("柜子实例不存在")
        db.withTransaction {
            var logId = log.id
            if (log.id == 0L) {
                logId = logDao.insert(log.copy(updatedAt = System.currentTimeMillis()))
            } else {
                logDao.update(log.copy(updatedAt = System.currentTimeMillis()))
            }
            faultDao.deleteForLog(logId)
            faults.forEach { f ->
                f.logId = logId
                faultDao.insert(f)
            }
            // 候选池自动沉淀：测试内容逐行 trim、去重后追加
            val existing = candDao.contentsOnce(inst.typeId).map { it.trim() }.toHashSet()
            log.testContent.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && existing.add(it) }
                .forEach { candDao.insert(CandidateItem(typeId = inst.typeId, content = it)) }
        }
    }

    suspend fun deleteLog(id: Long) {
        val l = logDao.getByIdOnce(id) ?: return
        logDao.delete(l)
    }

    suspend fun faultsOf(logId: Long) = faultDao.forLogOnce(logId)

    // ---------- 统计 / 导出 / 备份 ----------

    suspend fun stats(): Stats = Stats(
        projects = projectDao.count(),
        types = typeDao.count(),
        instances = instanceDao.count(),
        logs = logDao.count(),
        pendingFaults = faultDao.countPending()
    )

    suspend fun collectExport(): Pair<List<LogListItem>, List<FaultExportRow>> =
        logDao.exportAll() to faultDao.exportAll()

    companion object {
        const val BACKUP_APP_TAG = "power-debug-log"
        const val BACKUP_SCHEMA = 1
    }

    /**
     * 备份为 JSON 字符串。该格式同时是后期 PC/网页端的官方数据交换格式：
     * 字段名即数据库列名，schemaVersion 变更时需提供迁移说明。
     */
    suspend fun backupJson(): String {
        val jo = JSONObject()
        jo.put("app", BACKUP_APP_TAG)
        jo.put("schemaVersion", BACKUP_SCHEMA)
        jo.put("exportedAt", System.currentTimeMillis())

        fun arr(list: List<JSONObject>): JSONArray = JSONArray().apply { list.forEach(::put) }

        jo.put(
            "projects",
            arr(projectDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("code", it.code)
                    .put("remark", it.remark).put("createdAt", it.createdAt)
            })
        )
        jo.put(
            "cabinetTypes",
            arr(typeDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("remark", it.remark)
                    .put("createdAt", it.createdAt)
            })
        )
        jo.put(
            "candidateItems",
            arr(candDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("typeId", it.typeId).put("content", it.content)
                    .put("createdAt", it.createdAt)
            })
        )
        jo.put(
            "instances",
            arr(instanceDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("projectId", it.projectId).put("typeId", it.typeId)
                    .put("name", it.name).put("deviceCode", it.deviceCode)
                    .put("location", it.location).put("installer", it.installer)
                    .put("createdAt", it.createdAt)
            })
        )
        jo.put(
            "logs",
            arr(logDao.allOnce().map {
                JSONObject()
                    .put("id", it.id).put("instanceId", it.instanceId)
                    .put("circuit", it.circuit).put("testContent", it.testContent)
                    .put("tester", it.tester).put("remark", it.remark)
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
                    .put("status", it.status)
            })
        )
        return jo.toString(2)
    }

    /**
     * 从 JSON 恢复（整体覆盖）。解析失败会抛异常且不改动现有数据。
     */
    suspend fun restoreJson(text: String): Stats {
        val root = JSONObject(text)
        require(root.optString("app") == BACKUP_APP_TAG) { "不是本应用的备份文件" }

        val projects = mutableListOf<Project>()
        val types = mutableListOf<CabinetType>()
        val cands = mutableListOf<CandidateItem>()
        val instances = mutableListOf<CabinetInstance>()
        val logs = mutableListOf<DebugLog>()
        val faults = mutableListOf<FaultRecord>()

        root.optJSONArray("projects")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                projects += Project(
                    id = getLong("id"), name = getString("name"),
                    code = optString("code"), remark = optString("remark"),
                    createdAt = optLong("createdAt")
                )
            }
        }
        root.optJSONArray("cabinetTypes")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                types += CabinetType(
                    id = getLong("id"), name = getString("name"),
                    remark = optString("remark"), createdAt = optLong("createdAt")
                )
            }
        }
        root.optJSONArray("candidateItems")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                cands += CandidateItem(
                    id = getLong("id"), typeId = getLong("typeId"),
                    content = getString("content"), createdAt = optLong("createdAt")
                )
            }
        }
        root.optJSONArray("instances")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                instances += CabinetInstance(
                    id = getLong("id"), projectId = getLong("projectId"),
                    typeId = getLong("typeId"), name = getString("name"),
                    deviceCode = optString("deviceCode"), location = optString("location"),
                    installer = optString("installer"), createdAt = optLong("createdAt")
                )
            }
        }
        root.optJSONArray("logs")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                logs += DebugLog(
                    id = getLong("id"), instanceId = getLong("instanceId"),
                    circuit = optString("circuit"), testContent = getString("testContent"),
                    tester = optString("tester"), remark = optString("remark"),
                    createdAt = optLong("createdAt"), updatedAt = optLong("updatedAt")
                )
            }
        }
        root.optJSONArray("faults")?.let { a ->
            for (i in 0 until a.length()) with(a.getJSONObject(i)) {
                faults += FaultRecord(
                    id = getLong("id"), logId = getLong("logId"),
                    circuit = optString("circuit"), symptom = optString("symptom"),
                    solution = optString("solution"), occurredAt = optLong("occurredAt"),
                    resolvedAt = optLong("resolvedAt"), status = optInt("status")
                )
            }
        }

        db.withTransaction {
            faultDao.wipe(); logDao.wipe(); instanceDao.wipe()
            candDao.wipe(); typeDao.wipe(); projectDao.wipe()
            projectDao.insertAll(projects)
            typeDao.insertAll(types)
            candDao.insertAll(cands)
            instanceDao.insertAll(instances)
            logDao.insertAll(logs)
            faults.forEach { faultDao.insert(it) }
        }
        return Stats(projects.size, types.size, instances.size, logs.size, faults.count { it.status == FaultRecord.STATUS_PENDING })
    }
}
