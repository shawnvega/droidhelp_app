package com.bighelp.droidhelp.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bighelp.droidhelp.app.AppState
import com.bighelp.droidhelp.home.model.AppRoute

const val HOME_ROUTE = "home"

fun AppRoute.toRouteString(): String = when (this) {
    is AppRoute.Chat -> "chat/${conversationID}"
    AppRoute.Sessions -> "sessions"
    AppRoute.ScheduledTasks -> "scheduled_tasks"
    AppRoute.WorkspaceConnections -> "workspace_connections"
    AppRoute.Projects -> "projects"
    AppRoute.Kanban -> "kanban"
    AppRoute.Workflows -> "workflows"
}

fun navigateToRoute(navController: NavHostController, route: AppRoute) {
    navController.navigate(route.toRouteString())
}

fun popNavigationBack(navController: NavHostController): Boolean {
    return navController.popBackStack()
}

fun parseDeepLinkRoute(uriString: String): AppRoute? {
    val prefix = "droidhelp://"
    val path = uriString.removePrefix(prefix)
    val parts = path.split("/")
    val singlePartRoutes = mapOf(
        "sessions" to AppRoute.Sessions,
        "scheduled_tasks" to AppRoute.ScheduledTasks,
        "workspace_connections" to AppRoute.WorkspaceConnections,
        "projects" to AppRoute.Projects,
        "kanban" to AppRoute.Kanban,
        "workflows" to AppRoute.Workflows
    )

    return if (uriString.startsWith(prefix) && path.isNotEmpty()) {
        if (parts.size == 2 && parts[0] == "chat") {
            AppRoute.Chat(parts[1])
        } else if (parts.size == 1) {
            singlePartRoutes[parts[0]]
        } else {
            null
        }
    } else {
        null
    }
}

@Composable
fun AppNavHost(
    appState: AppState,
    navController: NavHostController = rememberNavController()
) {
    appState.path.let { }

    NavHost(
        navController = navController,
        startDestination = HOME_ROUTE
    ) {
        composable(HOME_ROUTE) {
            Text("Home")
        }
        composable("chat/{conversationID}") {
            Text("Chat")
        }
        composable("sessions") {
            Text("sessions")
        }
        composable("scheduled_tasks") {
            Text("scheduled_tasks")
        }
        composable("workspace_connections") {
            Text("workspace_connections")
        }
        composable("projects") {
            Text("projects")
        }
        composable("kanban") {
            Text("kanban")
        }
        composable("workflows") {
            Text("workflows")
        }
    }
}
