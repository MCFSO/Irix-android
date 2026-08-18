package org.mcfso.irix.data.model

import kotlinx.serialization.Serializable

/**
 * 客户端管理的节点条目（持久化到 DataStore）。
 * name 为用户可读名；address 形如 http://192.168.1.5:12346；
 * apiKey 为配对码或固定密钥（可空）。
 */
@Serializable
data class NodeConfig(
    val id: String,
    val name: String,
    val address: String,
    val apiKey: String = "",
    val selected: Boolean = false,
)
