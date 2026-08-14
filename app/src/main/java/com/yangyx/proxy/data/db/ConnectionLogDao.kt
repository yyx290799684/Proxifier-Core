package com.yangyx.proxy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yangyx.proxy.data.model.ConnectionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionLogDao {
    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<ConnectionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ConnectionLog)

    @Query("DELETE FROM connection_logs")
    suspend fun clearLogs()
}
