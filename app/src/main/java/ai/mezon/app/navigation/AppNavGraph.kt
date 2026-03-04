package ai.mezon.app.navigation

import ai.mezon.app.auth.LoginScreen
import ai.mezon.app.home.HomeScreen
import ai.mezon.app.home.chat.ChatScreen
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import java.net.URLDecoder

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(NavRoutes.LOGIN) {
            LoginScreen(
                onNavigateToHome = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(NavRoutes.HOME) {
            HomeScreen(
                onLogout = {
                    navController.navigate(NavRoutes.LOGIN) {
                        popUpTo(NavRoutes.HOME) { inclusive = true }
                    }
                },
                onOpenChat = { channelId, channelName, clanId, channelType ->
                    navController.navigate(
                        NavRoutes.chatRoute(channelId, channelName, clanId, channelType)
                    )
                }
            )
        }
        composable(
            route = NavRoutes.CHAT,
            arguments = listOf(
                navArgument("channelId") { type = NavType.LongType },
                navArgument("channelName") { type = NavType.StringType },
                navArgument("clanId") { type = NavType.LongType },
                navArgument("channelType") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getLong("channelId") ?: return@composable
            val channelName = backStackEntry.arguments?.getString("channelName")
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
            val clanId = backStackEntry.arguments?.getLong("clanId") ?: 0L
            val channelType = backStackEntry.arguments?.getInt("channelType") ?: 0

            ChatScreen(
                channelId = channelId,
                channelName = channelName,
                clanId = clanId,
                channelType = channelType,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
