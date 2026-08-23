package com.fieldlog.powerdebug.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun watchAllAsFlow(): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun allOnce(): List<Project>

    @Query(
        """SELECT pr.*, 
        (SELECT COUNT(*) FROM instances i WHERE i.projectId = pr.id) AS cabinetCount,
        (SELECT COUNT(*) FROM debug_logs l INNER JOIN instances i2 ON l.instanceId = i2.id WHERE i2.projectId = pr.id) AS logCount,
        (SELECT COUNT(*) FROM planned_items pi INNER JOIN instances i3 ON pi.instanceId = i3.id
            WHERE i3.projectId = pr.id AND pi.enabled = 1 AND pi.result = 0) AS pendingTests,
        (SELECT COUNT(*) FROM planned_items pi5 INNER JOIN instances i5 ON pi5.instanceId = i5.id
            WHERE i5.projectId = pr.id AND pi5.enabled = 1 AND pi5.result = 2) AS failedTests,
        (SELECT COUNT(*) FROM fault_records f INNER JOIN debug_logs l4 ON f.logId = l4.id
            INNER JOIN instances i4 ON l4.instanceId = i4.id
            WHERE i4.projectId = pr.id AND f.status = 0) AS pendingFaults
        FROM projects pr ORDER BY pr.createdAt DESC"""
    )
    fun watchListItemsAsFlow(): Flow<List<ProjectListItem>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getByIdOnce(id: String): Project?

    @Insert
    suspend fun insert(p: Project)

    @Insert
    suspend fun insertAll(list: List<Project>)

    @Update
    suspend fun update(p: Project)

    @Update
    suspend fun updateAll(list: List<Project>)

    @Delete
    suspend fun delete(p: Project)

    @Query("DELETE FROM projects")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}

@Dao
interface CabinetTypeDao {
    @Query("SELECT * FROM cabinet_types ORDER BY name")
    fun watchAllAsFlow(): Flow<List<CabinetType>>

    @Query("SELECT * FROM cabinet_types ORDER BY name")
    suspend fun allOnce(): List<CabinetType>

    @Query(
        """SELECT t.*,
        (SELECT COUNT(*) FROM instances i WHERE i.typeId = t.id) AS instanceCount,
        (SELECT COUNT(*) FROM candidate_items c WHERE c.typeId = t.id) AS itemCount
        FROM cabinet_types t ORDER BY t.name"""
    )
    fun watchListItemsAsFlow(): Flow<List<TypeListItem>>

    @Query("SELECT * FROM cabinet_types WHERE id = :id")
    suspend fun getByIdOnce(id: String): CabinetType?

    @Insert
    suspend fun insert(t: CabinetType)

    @Insert
    suspend fun insertAll(list: List<CabinetType>)

    @Update
    suspend fun update(t: CabinetType)

    @Update
    suspend fun updateAll(list: List<CabinetType>)

    @Delete
    suspend fun delete(t: CabinetType)

    @Query("DELETE FROM cabinet_types")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM cabinet_types")
    suspend fun count(): Int
}

@Dao
interface CandidateItemDao {
    @Query("SELECT * FROM candidate_items WHERE typeId = :typeId ORDER BY createdAt, id")
    fun watchByTypeAsFlow(typeId: String): Flow<List<CandidateItem>>

    @Query("SELECT * FROM candidate_items WHERE typeId = :typeId ORDER BY createdAt, id")
    suspend fun byTypeOnce(typeId: String): List<CandidateItem>

    @Query("SELECT content FROM candidate_items WHERE typeId = :typeId")
    suspend fun contentsOnce(typeId: String): List<String>

    @Query("SELECT * FROM candidate_items ORDER BY createdAt, id")
    suspend fun allOnce(): List<CandidateItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: CandidateItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CandidateItem>)

    @Delete
    suspend fun delete(item: CandidateItem)

    @Query("DELETE FROM candidate_items")
    suspend fun wipe()
}

@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances WHERE projectId = :projectId ORDER BY name")
    fun watchByProjectAsFlow(projectId: String): Flow<List<CabinetInstance>>

    /** 项目详情页柜子行：附带实时待测/未通过/待处理故障数（与调试日志页同源） */
    @Query(
        """SELECT i.*,
        (SELECT COUNT(*) FROM planned_items pi WHERE pi.instanceId = i.id AND pi.enabled = 1 AND pi.result = 0) AS pendingTests,
        (SELECT COUNT(*) FROM planned_items pi2 WHERE pi2.instanceId = i.id AND pi2.enabled = 1 AND pi2.result = 2) AS failedTests,
        (SELECT COUNT(*) FROM fault_records f INNER JOIN debug_logs l ON f.logId = l.id
            WHERE l.instanceId = i.id AND f.status = 0) AS pendingFaults
        FROM instances i WHERE i.projectId = :projectId ORDER BY i.name"""
    )
    fun watchByProjectWithStatsAsFlow(projectId: String): Flow<List<InstanceStatusRow>>

    @Query("SELECT * FROM instances WHERE projectId = :projectId ORDER BY name")
    suspend fun byProjectOnce(projectId: String): List<CabinetInstance>

    @Query(
        "SELECT * FROM instances WHERE (:projectId = '' OR projectId = :projectId) " +
            "AND (:typeId = '' OR typeId = :typeId) ORDER BY name"
    )
    suspend fun byProjectAndTypeOnce(projectId: String, typeId: String): List<CabinetInstance>

    @Query(
        """SELECT i.*, p.name AS projectName FROM instances i 
        INNER JOIN projects p ON i.projectId = p.id 
        WHERE i.typeId = :typeId ORDER BY p.name COLLATE NOCASE, i.name"""
    )
    suspend fun byTypeWithProject(typeId: String): List<InstanceRow>

    @Query("SELECT * FROM instances ORDER BY name")
    suspend fun allOnce(): List<CabinetInstance>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun getByIdOnce(id: String): CabinetInstance?

    @Insert
    suspend fun insert(i: CabinetInstance)

    @Insert
    suspend fun insertAll(list: List<CabinetInstance>)

    @Update
    suspend fun update(i: CabinetInstance)

    @Update
    suspend fun updateAll(list: List<CabinetInstance>)

    @Delete
    suspend fun delete(i: CabinetInstance)

    @Query("DELETE FROM instances")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM instances")
    suspend fun count(): Int
}

@Dao
interface DebugLogDao {
    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 0) AS pendingCount,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 1) AS resolvedCount
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE (:projectId = '' OR i.projectId = :projectId)
          AND (:typeId = '' OR i.typeId = :typeId)
          AND (:instanceId = '' OR l.instanceId = :instanceId)
          AND (:status = -1
               OR (:status = 0 AND EXISTS(SELECT 1 FROM fault_records fp WHERE fp.logId = l.id AND fp.status = 0))
               OR (:status = 1 AND EXISTS(SELECT 1 FROM fault_records fr WHERE fr.logId = l.id AND fr.status = 1)
                    AND NOT EXISTS(SELECT 1 FROM fault_records fn WHERE fn.logId = l.id AND fn.status = 0)))
          AND (:circuit = '' OR l.circuit LIKE '%' || :circuit || '%')
          AND (:q = '' OR l.testContent LIKE '%' || :q || '%'
               OR IFNULL(l.remark, '') LIKE '%' || :q || '%'
               OR IFNULL(l.tester, '') LIKE '%' || :q || '%'
               OR i.name LIKE '%' || :q || '%'
               OR p.name LIKE '%' || :q || '%'
               OR EXISTS(SELECT 1 FROM fault_records fs WHERE fs.logId = l.id
                    AND (fs.symptom LIKE '%' || :q || '%' OR fs.solution LIKE '%' || :q || '%' OR fs.circuit LIKE '%' || :q || '%')))
        ORDER BY l.createdAt DESC"""
    )
    suspend fun search(
        projectId: String, typeId: String, instanceId: String,
        status: Int, circuit: String, q: String
    ): List<LogListItem>

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 0) AS pendingCount,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 1) AS resolvedCount
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        WHERE l.id = :id LIMIT 1"""
    )
    suspend fun getDetailOnce(id: String): LogListItem?

    @Query(
        """SELECT l.*, p.name AS projectName, t.name AS typeName, i.name AS instanceName,
        i.deviceCode AS deviceCode, i.installer AS installer,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 0) AS pendingCount,
        (SELECT COUNT(*) FROM fault_records f WHERE f.logId = l.id AND f.status = 1) AS resolvedCount
        FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN cabinet_types t ON i.typeId = t.id
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name COLLATE NOCASE, l.createdAt"""
    )
    suspend fun exportAll(): List<LogListItem>

    @Query(
        """SELECT DISTINCT l.circuit FROM debug_logs l
        INNER JOIN instances i ON l.instanceId = i.id
        WHERE IFNULL(l.circuit, '') <> ''
          AND (:typeId = '' OR i.typeId = :typeId)
          AND (:projectId = '' OR i.projectId = :projectId)
        ORDER BY l.circuit LIMIT 40"""
    )
    suspend fun distinctCircuits(projectId: String, typeId: String): List<String>

    @Query("SELECT * FROM debug_logs ORDER BY createdAt, id")
    suspend fun allOnce(): List<DebugLog>

    @Query("SELECT * FROM debug_logs WHERE id = :id")
    suspend fun getByIdOnce(id: String): DebugLog?

    @Query("SELECT COUNT(*) FROM debug_logs WHERE instanceId = :instanceId")
    suspend fun countLogsOf(instanceId: String): Int

    @Insert
    suspend fun insert(l: DebugLog)

    @Insert
    suspend fun insertAll(list: List<DebugLog>)

    @Update
    suspend fun update(l: DebugLog)

    @Update
    suspend fun updateAll(list: List<DebugLog>)

    @Delete
    suspend fun delete(l: DebugLog)

    @Query("DELETE FROM debug_logs")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM debug_logs")
    suspend fun count(): Int
}

@Dao
interface FaultRecordDao {
    @Query("SELECT * FROM fault_records WHERE logId = :logId ORDER BY occurredAt")
    suspend fun forLogOnce(logId: String): List<FaultRecord>

    @Query("SELECT * FROM fault_records WHERE id IN (:ids)")
    suspend fun byIdsOnce(ids: List<String>): List<FaultRecord>

    @Query(
        """SELECT f.*, p.name AS projectName, i.name AS instanceName, i.deviceCode AS deviceCode
        FROM fault_records f
        INNER JOIN debug_logs l ON f.logId = l.id
        INNER JOIN instances i ON l.instanceId = i.id
        INNER JOIN projects p ON i.projectId = p.id
        ORDER BY p.name COLLATE NOCASE, i.name COLLATE NOCASE, f.occurredAt"""
    )
    suspend fun exportAll(): List<FaultExportRow>

    @Query("SELECT * FROM fault_records ORDER BY occurredAt, id")
    suspend fun allOnce(): List<FaultRecord>

    @Insert
    suspend fun insert(f: FaultRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<FaultRecord>)

    @Update
    suspend fun updateAll(list: List<FaultRecord>)

    @Query("DELETE FROM fault_records WHERE logId = :logId")
    suspend fun deleteForLog(logId: String)

    @Query("DELETE FROM fault_records")
    suspend fun wipe()

    @Query("SELECT COUNT(*) FROM fault_records WHERE status = 0")
    suspend fun countPending(): Int
}

@Dao
interface PlannedItemDao {
    /** 管理页：未完成(未测+未通过)在前，未通过的排最前 */
    @Query(
        """SELECT * FROM planned_items WHERE instanceId = :instanceId 
        ORDER BY CASE WHEN result = 1 THEN 1 ELSE 0 END, result DESC, createdAt, id"""
    )
    fun watchByInstanceAsFlow(instanceId: String): Flow<List<PlannedItem>>

    @Query("SELECT content FROM planned_items WHERE instanceId = :instanceId")
    suspend fun contentsOnce(instanceId: String): List<String>

    @Query("SELECT * FROM planned_items WHERE id IN (:ids)")
    suspend fun byIdsOnce(ids: List<String>): List<PlannedItem>

    /** 开始测试清单：启用且尚未通过(含上次未通过，供复测) */
    @Query(
        """SELECT * FROM planned_items WHERE instanceId = :instanceId AND enabled = 1 AND result <> 1 
        ORDER BY result DESC, createdAt, id"""
    )
    suspend fun pendingForTestOnce(instanceId: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items WHERE instanceId = :instanceId ORDER BY result, createdAt, id")
    suspend fun allOfInstanceOnce(instanceId: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items WHERE logId = :logId")
    suspend fun forLogOnce(logId: String): List<PlannedItem>

    @Query("SELECT * FROM planned_items ORDER BY updatedAt, id")
    suspend fun allOnce(): List<PlannedItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: PlannedItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<PlannedItem>)

    @Update
    suspend fun update(p: PlannedItem)

    @Update
    suspend fun updateAll(list: List<PlannedItem>)

    @Delete
    suspend fun delete(p: PlannedItem)

    /** 批量记录测试结果并挂到生成它的日志上（未通过时逐项携带faultId） */
    @Query(
        "UPDATE planned_items SET result = :result, doneAt = :at, logId = :logId, " +
            "faultId = :faultId, updatedAt = :at WHERE id IN (:ids)"
    )
    suspend fun setResult(ids: List<String>, result: Int, at: Long, logId: String, faultId: String)

    /** 删除日志时选择"重测"：恢复为未测 */
    @Query("UPDATE planned_items SET result = 0, doneAt = 0, logId = '', faultId = '', updatedAt = :at WHERE logId = :logId")
    suspend fun resetForLog(logId: String, at: Long)

    /** 删除日志时选择"连项删除"：这些预选项可能是误添加的 */
    @Query("DELETE FROM planned_items WHERE logId = :logId")
    suspend fun deleteForLog(logId: String)

    @Query("DELETE FROM planned_items")
    suspend fun wipe()
}

@Dao
interface TesterAccountDao {
    @Query("SELECT * FROM tester_accounts ORDER BY createdAt")
    suspend fun allOnce(): List<TesterAccount>

    @Query("SELECT * FROM tester_accounts WHERE username = :username LIMIT 1")
    suspend fun byUsername(username: String): TesterAccount?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(a: TesterAccount)

    @Query("UPDATE tester_accounts SET source = :source WHERE username = :username")
    suspend fun updateSource(username: String, source: String)
}

@Dao
interface DebuggerDao {
    @Query("SELECT * FROM debuggers ORDER BY createdAt")
    suspend fun allOnce(): List<Debugger>

    @Query("SELECT * FROM debuggers WHERE id = :id LIMIT 1")
    suspend fun byIdOnce(id: String): Debugger?

    @Query("SELECT * FROM debuggers WHERE name = :name LIMIT 1")
    suspend fun byNameOnce(name: String): Debugger?

    /** IGNORE策略：重名静默跳过，唯一性由唯一索引兜底 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(d: Debugger)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Debugger>)

    @Update
    suspend fun updateAll(list: List<Debugger>)

    @Delete
    suspend fun delete(d: Debugger)

    @Query("DELETE FROM debuggers")
    suspend fun wipe()
}
