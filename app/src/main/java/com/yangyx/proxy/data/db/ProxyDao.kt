package com.yangyx.proxy.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yangyx.proxy.data.model.ProxyServer
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyDao {
    @Query("SELECT * FROM proxy_servers ORDER BY id DESC")
    fun getAllProxies(): Flow<List<ProxyServer>>

    @Query("SELECT * FROM proxy_servers WHERE id = :id")
    suspend fun getProxyById(id: Long): ProxyServer?

    @Query("SELECT * FROM proxy_servers WHERE isActive = 1 LIMIT 1")
    fun getActiveProxy(): Flow<ProxyServer?>

    @Query("SELECT * FROM proxy_servers WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProxySync(): ProxyServer?

    @Query("SELECT * FROM proxy_servers")
    suspend fun getAllProxiesSync(): List<ProxyServer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProxy(server: ProxyServer): Long

    @Update
    suspend fun updateProxy(server: ProxyServer)

    @Delete
    suspend fun deleteProxy(server: ProxyServer)

    @Query("UPDATE proxy_servers SET isActive = 0")
    suspend fun clearActiveProxies()

    @Query("UPDATE proxy_servers SET isActive = CASE WHEN id = :targetId THEN 1 ELSE 0 END")
    suspend fun setActiveProxy(targetId: Long)

    @Query("UPDATE proxy_servers SET latencyMs = :latency WHERE id = :id")
    suspend fun updateLatency(id: Long, latency: Int?)
}
