package com.mezon.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.RolePermissionInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val roleCacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class RolePermissionInfoRow(
    val permissionId: Long,
    val slug: String,
    val title: String,
    val active: Boolean,
    val level: Int,
)

@Serializable
internal data class ClanRoleExtraPayload(
    val permissionSlugs: List<String>,
    val rolePermissions: List<RolePermissionInfoRow>,
    val channelIds: List<Long>,
)

@Entity(tableName = "clan_role_list_meta")
data class ClanRoleListMetaEntity(
    @PrimaryKey val clanId: Long,
    val listRolesSelfMaxLevel: Int,
)

@Entity(
    tableName = "clan_role_cache",
    primaryKeys = ["clanId", "roleId"],
)
data class ClanRoleCacheEntity(
    val clanId: Long,
    val roleId: Long,
    val title: String,
    val color: Int,
    val colorHexRaw: String,
    val iconUrl: String,
    val slug: String,
    val maxLevelPermission: Int,
    val memberCount: Int,
    val roleChannelActive: Int,
    val extraJson: String,
)

@Dao
interface ClanRoleListMetaDao {
    @Query("SELECT * FROM clan_role_list_meta")
    suspend fun getAll(): List<ClanRoleListMetaEntity>

    @Upsert
    suspend fun upsert(row: ClanRoleListMetaEntity)

    @Query("DELETE FROM clan_role_list_meta WHERE clanId = :clanId")
    suspend fun deleteByClan(clanId: Long)

    @Query("DELETE FROM clan_role_list_meta")
    suspend fun deleteAll()
}

@Dao
interface ClanRoleCacheDao {
    @Query("SELECT * FROM clan_role_cache")
    suspend fun getAll(): List<ClanRoleCacheEntity>

    @Query("DELETE FROM clan_role_cache WHERE clanId = :clanId")
    suspend fun deleteForClan(clanId: Long)

    @Query("DELETE FROM clan_role_cache")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(items: List<ClanRoleCacheEntity>)

    @Transaction
    suspend fun replaceClan(clanId: Long, items: List<ClanRoleCacheEntity>) {
        deleteForClan(clanId)
        if (items.isNotEmpty()) {
            insertAll(items)
        }
    }
}

internal fun ClanRole.toCacheEntity(): ClanRoleCacheEntity {
    val payload = ClanRoleExtraPayload(
        permissionSlugs = permissionSlugs,
        rolePermissions = rolePermissions.map {
            RolePermissionInfoRow(
                permissionId = it.permissionId,
                slug = it.slug,
                title = it.title,
                active = it.active,
                level = it.level,
            )
        },
        channelIds = channelIds,
    )
    return ClanRoleCacheEntity(
        clanId = clanId,
        roleId = roleId,
        title = title,
        color = color,
        colorHexRaw = colorHexRaw,
        iconUrl = iconUrl,
        slug = slug,
        maxLevelPermission = maxLevelPermission,
        memberCount = memberCount,
        roleChannelActive = roleChannelActive,
        extraJson = roleCacheJson.encodeToString(ClanRoleExtraPayload.serializer(), payload),
    )
}

internal fun ClanRoleCacheEntity.toClanRole(): ClanRole? = try {
    val p = roleCacheJson.decodeFromString(ClanRoleExtraPayload.serializer(), extraJson)
    ClanRole(
        roleId = roleId,
        clanId = clanId,
        title = title,
        color = color,
        colorHexRaw = colorHexRaw,
        iconUrl = iconUrl,
        slug = slug,
        permissionSlugs = p.permissionSlugs,
        maxLevelPermission = maxLevelPermission,
        memberCount = memberCount,
        rolePermissions = p.rolePermissions.map {
            RolePermissionInfo(
                permissionId = it.permissionId,
                slug = it.slug,
                title = it.title,
                active = it.active,
                level = it.level,
            )
        },
        roleChannelActive = roleChannelActive,
        channelIds = p.channelIds,
    )
} catch (_: Exception) {
    null
}
