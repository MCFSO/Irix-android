package org.mcfso.irix.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import org.mcfso.irix.R

/** 主导航目的地（底部导航栏）。 */
enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    OVERVIEW("overview", R.string.nav_overview, Icons.Outlined.Dashboard),
    INSTANCES("instances", R.string.nav_instances, Icons.Outlined.Inventory2),
    FILES("files", R.string.nav_files, Icons.Outlined.Storage),
    CONTAINERS("containers", R.string.nav_containers, Icons.Outlined.SettingsEthernet),
    NODES("nodes", R.string.nav_nodes, Icons.Outlined.Dns),
}

object Routes {
    const val INSTANCE_DETAIL = "instance/{uuid}"
    const val FILES_DETAIL = "files/{uuid}"
    const val FILE_EDIT = "file/edit/{uuid}/{path:.+}"
    const val FILES_TRASH = "files/{uuid}/trash"

    fun instanceDetail(uuid: String) = "instance/$uuid"
    fun filesDetail(uuid: String) = "files/$uuid"
    fun fileEdit(uuid: String, path: String) = "file/edit/$uuid/${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun filesTrash(uuid: String) = "files/$uuid/trash"
}
