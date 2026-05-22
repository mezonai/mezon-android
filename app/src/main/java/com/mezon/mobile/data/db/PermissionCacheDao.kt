package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Entity(tableName = "permission_catalog")
data class PermissionCatalogEntity(
    @PrimaryKey val permissionId: Long,
    val slug: String,
    val title: String,
    val description: String,
    val level: Int,
    val scope: Int,
)

@Entity(tableName = "clan_user_max_permission")
data class ClanUserMaxPermissionEntity(
    @PrimaryKey val clanId: Long,
    val maxLevel: Int,
    val updatedAtMillis: Long,
)

@Dao
interface PermissionCatalogDao {
    @Query("SELECT * FROM permission_catalog")
    suspend fun getAll(): List<PermissionCatalogEntity>

    @Query("DELETE FROM permission_catalog")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(items: List<PermissionCatalogEntity>)

    @Transaction
    suspend fun replaceAll(items: List<PermissionCatalogEntity>) {
        deleteAll()
        if (items.isNotEmpty()) {
            insertAll(items)
        }
    }
}

@Dao
interface ClanUserMaxPermissionDao {
    @Query("SELECT * FROM clan_user_max_permission")
    suspend fun getAll(): List<ClanUserMaxPermissionEntity>

    @Query("SELECT * FROM clan_user_max_permission WHERE clanId = :clanId LIMIT 1")
    suspend fun getForClan(clanId: Long): ClanUserMaxPermissionEntity?

    @Upsert
    suspend fun upsert(row: ClanUserMaxPermissionEntity)

    @Query("DELETE FROM clan_user_max_permission WHERE clanId = :clanId")
    suspend fun deleteByClan(clanId: Long)

    @Query("DELETE FROM clan_user_max_permission")
    suspend fun deleteAll()
}
