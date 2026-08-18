package org.mcfso.irix.data

import java.net.InetAddress
import java.net.URI

/**
 * 明文 HTTP 访问策略（与 network_security_config.xml 的约定一致）：
 * 仅允许回环 / 局域网私有 / 链路本地地址与 localhost 使用 http://，
 * 公网 IP 与域名一律要求 https://。
 *
 * 系统网络安全配置（network-security-config）不支持网段/私有地址范围匹配，
 * 无法在配置层只放行私有网段，因此在本类做应用层校验：
 * 节点编辑保存时（NodesScreen 对话框）与请求构建时（ApiFactory）双重拦截。
 */
object NetworkPolicy {

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * 检查地址是否允许使用明文 HTTP。
     * @return 违规原因（中文消息）；合规返回 null（https 地址一律放行）。
     */
    fun cleartextViolation(address: String): String? {
        val trimmed = address.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return null
        val host = runCatching { URI(trimmed).host }
            .getOrNull()
            ?.removePrefix("[")
            ?.removeSuffix("]")
        if (host.isNullOrEmpty()) return "地址格式无效"
        if (host.equals("localhost", ignoreCase = true)) return null
        return if (isPrivateIpLiteral(host)) {
            null
        } else {
            "公网或域名地址必须使用 HTTPS；明文 HTTP 仅允许局域网私有 IP 与 localhost"
        }
    }

    /** 判断 host 是否为回环 / 私有 / 链路本地的 IP 字面量（不做 DNS 解析）。 */
    private fun isPrivateIpLiteral(host: String): Boolean {
        if (host.contains(':')) {
            // IPv6 字面量：解析不触发 DNS
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress
        }
        val m = IPV4.matchEntire(host) ?: return false
        val parts = m.groupValues.drop(1).map { it.toInt() }
        if (parts.any { it > 255 }) return false
        val a = parts[0]
        val b = parts[1]
        return when {
            a == 127 -> true                    // 回环 127.0.0.0/8
            a == 10 -> true                     // 私有 10.0.0.0/8
            a == 172 && b in 16..31 -> true     // 私有 172.16.0.0/12
            a == 192 && b == 168 -> true        // 私有 192.168.0.0/16
            a == 169 && b == 254 -> true        // 链路本地 169.254.0.0/16
            else -> false
        }
    }
}
