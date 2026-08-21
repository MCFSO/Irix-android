package org.mcfso.irix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.mcfso.irix.ui.SessionViewModel
import org.mcfso.irix.ui.common.EmptyBox
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState
import org.mcfso.irix.ui.containers.ContainersScreen
import org.mcfso.irix.ui.containers.ContainersViewModel
import org.mcfso.irix.ui.files.FileEditScreen
import org.mcfso.irix.ui.files.FilesScreen
import org.mcfso.irix.ui.files.FilesViewModel
import org.mcfso.irix.ui.instances.CreateInstanceDialog
import org.mcfso.irix.ui.instances.InstanceDetailScreen
import org.mcfso.irix.ui.instances.InstanceDetailViewModel
import org.mcfso.irix.ui.instances.InstancesScreen
import org.mcfso.irix.ui.instances.InstancesViewModel
import org.mcfso.irix.ui.navigation.Routes
import org.mcfso.irix.ui.navigation.TopDestination
import org.mcfso.irix.ui.nodes.NodesScreen
import org.mcfso.irix.ui.overview.OverviewScreen
import org.mcfso.irix.ui.theme.IrixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IrixTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IrixApp()
                }
            }
        }
    }
}

@Composable
fun IrixApp() {
    val navController = rememberNavController()
    val session: SessionViewModel = viewModel()
    val selected by session.selected.collectAsState()
    val instancesVm: InstancesViewModel = viewModel()
    val detailVm: InstanceDetailViewModel = viewModel()
    val filesVm: FilesViewModel = viewModel()
    val containersVm: ContainersViewModel = viewModel()
    var showCreate by remember { mutableStateOf(false) }

    // 会话级消息（如概览刷新失败）→ 全局 Snackbar
    val toast by session.toast.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(toast) { session.consumeToast() }

    // 概览 15s 周期轮询：进入应用即开始，组合销毁时自动停止
    DisposableEffect(Unit) {
        session.startPolling()
        onDispose { session.stopPolling() }
    }

    // 当前路由：用于导航栏高亮（详情/文件编辑等二级页归属其一级目的地）
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Box(Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                TopDestination.entries.forEach { dest ->
                    item(
                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                        label = { Text(stringResource(dest.labelRes)) },
                        selected = isDestinationSelected(currentRoute, dest),
                        onClick = { navController.navigate(dest.route) { launchSingleTop = true } },
                    )
                }
            },
        ) {
            NavHost(navController, startDestination = TopDestination.OVERVIEW.route) {
                composable(TopDestination.OVERVIEW.route) { OverviewScreen(session) }
                composable(TopDestination.INSTANCES.route) {
                    InstancesScreen(
                        session = session,
                        vm = instancesVm,
                        onOpenInstance = { uuid -> navController.navigate(Routes.instanceDetail(uuid)) },
                        onAdd = { showCreate = true },
                    )
                }
                composable(TopDestination.FILES.route) {
                    val node = selected
                    if (node == null) EmptyBox(stringResource(R.string.files_no_node_hint))
                    else NoInstanceFilesHint(session, filesVm, node)
                }
                composable(TopDestination.CONTAINERS.route) {
                    ContainersScreen(session, containersVm)
                }
                composable(TopDestination.NODES.route) {
                    NodesScreen(session)
                }
                composable(Routes.INSTANCE_DETAIL) { entry ->
                    val uuid = entry.arguments?.getString("uuid") ?: return@composable
                    val node = selected ?: return@composable
                    InstanceDetailScreen(
                        uuid = uuid,
                        node = node,
                        vm = detailVm,
                        onBack = { navController.popBackStack() },
                        onOpenFiles = { instUuid -> navController.navigate(Routes.filesDetail(instUuid)) },
                    )
                }
                composable(Routes.FILES_DETAIL) { entry ->
                    val uuid = entry.arguments?.getString("uuid") ?: return@composable
                    val node = selected ?: return@composable
                    FilesScreen(
                        uuid = uuid,
                        node = node,
                        vm = filesVm,
                        onBack = { navController.popBackStack() },
                        onEdit = { path -> navController.navigate(Routes.fileEdit(uuid, path)) },
                        onOpenTrash = { navController.navigate(Routes.filesTrash(uuid)) },
                    )
                }
                composable(Routes.FILES_TRASH) { entry ->
                    val uuid = entry.arguments?.getString("uuid") ?: return@composable
                    val node = selected ?: return@composable
                    val trashVm: org.mcfso.irix.ui.files.TrashViewModel = viewModel()
                    org.mcfso.irix.ui.files.TrashScreen(
                        uuid = uuid,
                        node = node,
                        vm = trashVm,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.FILE_EDIT) { entry ->
                    val uuid = entry.arguments?.getString("uuid") ?: return@composable
                    val pathEncoded = entry.arguments?.getString("path") ?: return@composable
                    val path = java.net.URLDecoder.decode(pathEncoded, "UTF-8")
                    val node = selected ?: return@composable
                    FileEditScreen(
                        uuid = uuid,
                        path = path,
                        node = node,
                        vm = filesVm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (showCreate) {
        val node = selected
        if (node != null) {
            CreateInstanceDialog(
                onDismiss = { showCreate = false },
                onCreate = { config -> instancesVm.create(node, config); showCreate = false },
            )
        } else {
            showCreate = false
        }
    }
}

/** 二级页（实例详情/文件浏览/文件编辑）归属其一级目的地高亮。 */
private fun isDestinationSelected(currentRoute: String?, dest: TopDestination): Boolean = when (currentRoute) {
    dest.route -> true
    Routes.INSTANCE_DETAIL -> dest == TopDestination.INSTANCES
    Routes.FILES_DETAIL -> dest == TopDestination.FILES
    Routes.FILE_EDIT -> dest == TopDestination.FILES
    else -> false
}

@Composable
private fun NoInstanceFilesHint(
    session: SessionViewModel,
    filesVm: FilesViewModel,
    node: org.mcfso.irix.data.model.NodeConfig,
) {
    // 文件管理基于实例：提示从实例进入
    EmptyBox(stringResource(R.string.files_hint))
}
