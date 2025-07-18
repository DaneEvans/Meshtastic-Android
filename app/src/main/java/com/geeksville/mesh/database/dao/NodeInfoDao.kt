/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.geeksville.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.geeksville.mesh.android.BuildUtils.warn
import com.geeksville.mesh.copy
import com.geeksville.mesh.database.entity.MetadataEntity
import com.geeksville.mesh.database.entity.MyNodeEntity
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.database.entity.NodeWithRelations
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions")
@Dao
interface NodeInfoDao {

    @Query("SELECT * FROM my_node")
    fun getMyNodeInfo(): Flow<MyNodeEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setMyNodeInfo(myInfo: MyNodeEntity)

    @Query("DELETE FROM my_node")
    fun clearMyNodeInfo()

    @Query(
        """
        SELECT * FROM nodes
        ORDER BY CASE
            WHEN num = (SELECT myNodeNum FROM my_node LIMIT 1) THEN 0
            ELSE 1
        END,
        last_heard DESC
        """
    )
    @Transaction
    fun nodeDBbyNum(): Flow<Map<@MapColumn(columnName = "num") Int, NodeWithRelations>>

    @Query(
        """
    WITH OurNode AS (
        SELECT latitude, longitude
        FROM nodes
        WHERE num = (SELECT myNodeNum FROM my_node LIMIT 1)
    )
    SELECT * FROM nodes
    WHERE (:includeUnknown = 1 OR short_name IS NOT NULL)
        AND (:filter = ''
            OR (long_name LIKE '%' || :filter || '%'
            OR short_name LIKE '%' || :filter || '%'
            OR printf('!%08x', CASE WHEN num < 0 THEN num + 4294967296 ELSE num END) LIKE '%' || :filter || '%'
            OR CAST(CASE WHEN num < 0 THEN num + 4294967296 ELSE num END AS TEXT) LIKE '%' || :filter || '%'))
        AND (:lastHeardMin = -1 OR last_heard >= :lastHeardMin)
        AND (:hopsAwayMax = -1 OR (hops_away <= :hopsAwayMax AND hops_away >= 0) OR num = (SELECT myNodeNum FROM my_node LIMIT 1))
    ORDER BY CASE
        WHEN num = (SELECT myNodeNum FROM my_node LIMIT 1) THEN 0
        ELSE 1
    END,
    CASE
        WHEN :sort = 'last_heard' THEN last_heard * -1
        WHEN :sort = 'alpha' THEN UPPER(long_name) 
        WHEN :sort = 'distance' THEN
            CASE
                WHEN latitude IS NULL OR longitude IS NULL OR
                    (latitude = 0.0 AND longitude = 0.0) THEN 999999999
                ELSE
                    (latitude - (SELECT latitude FROM OurNode)) *
                    (latitude - (SELECT latitude FROM OurNode)) +
                    (longitude - (SELECT longitude FROM OurNode)) *
                    (longitude - (SELECT longitude FROM OurNode))
            END
        WHEN :sort = 'hops_away' THEN
            CASE
                WHEN hops_away = -1 THEN 999999999
                ELSE hops_away
            END
        WHEN :sort = 'channel' THEN channel
        WHEN :sort = 'via_mqtt' THEN via_mqtt
        WHEN :sort = 'via_favorite' THEN is_favorite * -1
        ELSE 0
    END ASC,
    last_heard DESC
    """
    )
    @Transaction
    fun getNodes(
        sort: String,
        filter: String,
        includeUnknown: Boolean,
        hopsAwayMax: Int,
        lastHeardMin: Int,
    ): Flow<List<NodeWithRelations>>

    @Upsert
    fun upsert(node: NodeEntity) {
        val found = getNodeByNum(node.num)?.node
        found?.let {
            val keyMatch = !it.hasPKC || it.user.publicKey == node.user.publicKey
            it.user = if (keyMatch) {
                node.user
            } else {
                node.user.copy {
                    warn("Public key mismatch from $longName ($shortName)")
                    publicKey = NodeEntity.ERROR_BYTE_STRING
                }
            }
        }
        doUpsert(node)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putAll(nodes: List<NodeEntity>) {
        nodes.forEach { node ->
            val found = getNodeByNum(node.num)?.node
            found?.let {
                val keyMatch = !it.hasPKC || it.user.publicKey == node.user.publicKey
                it.user = if (keyMatch) {
                    node.user
                } else {
                    node.user.copy {
                        warn("Public key mismatch from $longName ($shortName)")
                        publicKey = NodeEntity.ERROR_BYTE_STRING
                    }
                }
            }
        }
        doPutAll(nodes)
    }

    @Query("DELETE FROM nodes")
    fun clearNodeInfo()

    @Query("DELETE FROM nodes WHERE num=:num")
    fun deleteNode(num: Int)

    @Upsert
    fun upsert(meta: MetadataEntity)

    @Query("DELETE FROM metadata WHERE num=:num")
    fun deleteMetadata(num: Int)

    @Query("SELECT * FROM nodes WHERE num=:num")
    @Transaction
    fun getNodeByNum(num: Int): NodeWithRelations?

    @Upsert
    fun doUpsert(node: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun doPutAll(nodes: List<NodeEntity>)
}
