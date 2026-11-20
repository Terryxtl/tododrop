package com.terryxtl.tododrop.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.terryxtl.tododrop.ui.backup.BackupScreen
import com.terryxtl.tododrop.ui.export.ExportSelectScreen
import com.terryxtl.tododrop.ui.history.HistoryScreen
import com.terryxtl.tododrop.ui.projects.ProjectsScreen
import com.terryxtl.tododrop.ui.scan.ScanScreen
import com.terryxtl.tododrop.ui.send.SendScreen
import kotlinx.serialization.Serializable

@Serializable
data object ProjectsRoute

/** [projectId] 为 [ALL] 时预选全部未完成待办，否则只预选该项目。 */
@Serializable
data class ExportRoute(val projectId: Long = ALL) {
    companion object {
        const val ALL = -1L
    }
}

@Serializable
data class ScanRoute(val todoIds: List<Long>)

@Serializable
data class SendRoute(val todoIds: List<Long>, val qr: String)

@Serializable
data object HistoryRoute

@Serializable
data object BackupRoute

@Composable
fun TodoDropNavHost() {
    val nav = rememberNavController()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    NavHost(
        navController = nav,
        startDestination = ProjectsRoute,
        enterTransition = { slideInHorizontally(spatial) { it / 4 } + fadeIn(effects) },
        exitTransition = { slideOutHorizontally(spatial) { -it / 8 } + fadeOut(effects) },
        popEnterTransition = { slideInHorizontally(spatial) { -it / 8 } + fadeIn(effects) },
        popExitTransition = { slideOutHorizontally(spatial) { it / 4 } + fadeOut(effects) },
    ) {
        composable<ProjectsRoute> {
            ProjectsScreen(
                onExportAll = { nav.navigate(ExportRoute()) },
                onExportProject = { projectId -> nav.navigate(ExportRoute(projectId)) },
                onQuickExport = { ids -> nav.navigate(ScanRoute(ids)) },
                onHistory = { nav.navigate(HistoryRoute) },
                onBackup = { nav.navigate(BackupRoute) },
            )
        }
        composable<ExportRoute> {
            ExportSelectScreen(
                onBack = { nav.popBackStack() },
                onScan = { ids -> nav.navigate(ScanRoute(ids)) },
            )
        }
        composable<ScanRoute> { entry ->
            val route = entry.toRoute<ScanRoute>()
            ScanScreen(
                onBack = { nav.popBackStack() },
                onScanned = { qr ->
                    nav.navigate(SendRoute(route.todoIds, qr)) {
                        popUpTo<ScanRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<SendRoute> { entry ->
            val route = entry.toRoute<SendRoute>()
            SendScreen(
                onBack = { nav.popBackStack() },
                onDone = { nav.popBackStack<ProjectsRoute>(inclusive = false) },
                onRescan = {
                    nav.navigate(ScanRoute(route.todoIds)) {
                        popUpTo<SendRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<HistoryRoute> { HistoryScreen(onBack = { nav.popBackStack() }) }
        composable<BackupRoute> { BackupScreen(onBack = { nav.popBackStack() }) }
    }
}
