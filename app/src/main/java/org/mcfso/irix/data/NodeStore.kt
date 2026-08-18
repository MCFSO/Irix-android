package org.mcfso.irix.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.mcfso.irix.data.api.ApiFactory
import org.mcfso.irix.data.model.NodeConfig
import java.util.UUID

/**
 * 节点配置仓库：持久化到 DataStore（JSON 序列化）。
 * 提供 CRUD + 当前选中节点的流式订阅。
 */
class NodeStore(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("nodes_json")

    val nodes: Flow<List<NodeConfig>> = store.data.map { prefs ->
        prefs[key]?.let {
            ApiFactory.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(NodeConfig.serializer()),
                it,
            )
        } ?: emptyList()
    }

    val selectedNode: Flow<NodeConfig?> = nodes.map { list -> list.firstOrNull { it.selected } ?: list.firstOrNull() }

    private suspend fun snapshot(): List<NodeConfig> = nodes.first()

    private suspend fun save(list: List<NodeConfig>) {
        store.edit { prefs ->
            prefs[key] = ApiFactory.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(NodeConfig.serializer()),
                list,
            )
        }
    }

    suspend fun add(name: String, address: String, apiKey: String): NodeConfig {
        val node = NodeConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            address = address,
            apiKey = apiKey,
        )
        val list = snapshot()
        // 第一个节点默认选中
        val withNode = if (list.isEmpty()) list + node.copy(selected = true) else list + node
        save(withNode)
        return node
    }

    suspend fun update(id: String, name: String, address: String, apiKey: String) {
        val updated = snapshot().map {
            if (it.id == id) it.copy(name = name, address = address, apiKey = apiKey) else it
        }
        save(updated)
    }

    suspend fun delete(id: String) {
        val current = snapshot()
        val remaining = current.filterNot { it.id == id }
        // 删除的若是选中节点，选中第一个
        val wasSelected = current.firstOrNull { it.id == id }?.selected == true
        val fixed = if (wasSelected && remaining.isNotEmpty()) {
            remaining.mapIndexed { i, n -> n.copy(selected = i == 0) }
        } else {
            remaining
        }
        save(fixed)
    }

    suspend fun select(id: String) {
        val updated = snapshot().map { it.copy(selected = it.id == id) }
        save(updated)
    }

    suspend fun get(id: String): NodeConfig? = snapshot().firstOrNull { it.id == id }
}
