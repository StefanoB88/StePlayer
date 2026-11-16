package com.steplayer.app.ui.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.steplayer.app.ui.MainShell
import com.steplayer.app.ui.live.GuideScreen
import com.steplayer.app.ui.onboarding.AddSourceScreen
import com.steplayer.app.ui.onboarding.OnboardingScreen
import com.steplayer.app.ui.onboarding.SyncScreen
import com.steplayer.app.ui.search.SearchScreen
import com.steplayer.app.ui.settings.CategoriesScreen
import com.steplayer.app.ui.settings.SettingsScreen
import com.steplayer.app.ui.vod.MovieDetailScreen
import com.steplayer.app.ui.vod.SeriesDetailScreen
import com.steplayer.shared.model.ContentType

@Composable
fun AppNavHost(startDestination: String, pendingRoute: String? = null, onRouteHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val actions = remember(nav) { AppActions(nav, context) }

    androidx.compose.runtime.LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        // Also after a first-run onboarding (start destination ONBOARDING, then MAIN): only "is the main
        // screen in the back stack" matters, otherwise the player's GUIDE key and "Perché si blocca?"
        // did nothing until the app was restarted.
        val onMain = startDestination == Routes.MAIN || runCatching { nav.getBackStackEntry(Routes.MAIN) }.isSuccess
        if (onMain) nav.navigate(r)
        onRouteHandled()
    }

    NavHost(
        navController = nav,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { fadeOut(tween(150)) },
    ) {
        composable(Routes.ONBOARDING) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val app = com.steplayer.app.ui.rememberApp()
            OnboardingScreen(onContinue = {
                scope.launch {
                    if (app.core.sources.current() != null) actions.goToMainClearingStack()
                    else nav.navigate("add_source?editId=-1&first=true")
                }
            })
        }
        composable(
            Routes.ADD_SOURCE,
            arguments = listOf(
                navArgument("editId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("first") { type = NavType.BoolType; defaultValue = false },
                navArgument("phone") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val editId = entry.arguments?.getLong("editId") ?: -1L
            AddSourceScreen(
                editId = editId.takeIf { it >= 0 },
                onBack = if (entry.arguments?.getBoolean("first") == true) null else ({ actions.back() }),
                onSaved = { id, isNew -> if (isNew) actions.goToSync(id) else actions.back() },
                preferPhone = entry.arguments?.getBoolean("phone") == true,
            )
        }
        composable(Routes.SYNC, arguments = listOf(navArgument("sourceId") { type = NavType.LongType })) { entry ->
            SyncScreen(sourceId = entry.arguments!!.getLong("sourceId"), onDone = { actions.goToMainClearingStack() })
        }
        composable(Routes.MAIN) { MainShell(actions) }
        composable(Routes.SEARCH) { SearchScreen(actions) }
        composable(Routes.SETTINGS) { SettingsScreen(actions) }
        composable(
            Routes.MOVIE,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType }, navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            MovieDetailScreen(entry.arguments!!.getLong("sourceId"), entry.arguments!!.getString("id")!!, actions)
        }
        composable(
            Routes.SERIES,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType }, navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            SeriesDetailScreen(entry.arguments!!.getLong("sourceId"), entry.arguments!!.getString("id")!!, actions)
        }
        composable(
            Routes.GUIDE,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType }, navArgument("categoryId") { type = NavType.StringType }),
        ) { entry ->
            GuideScreen(entry.arguments!!.getLong("sourceId"), entry.arguments!!.getString("categoryId")!!, actions)
        }
        composable(Routes.CONNTEST, arguments = listOf(navArgument("sourceId") { type = NavType.LongType })) { entry ->
            com.steplayer.app.ui.settings.ConnectionTestScreen(entry.arguments!!.getLong("sourceId"), actions)
        }
        composable(Routes.SUBTITLES) { com.steplayer.app.ui.settings.SubtitleSettingsScreen(actions) }
        composable(Routes.LICENSES) { com.steplayer.app.ui.settings.LicensesScreen(actions) }
        composable(Routes.SEND_TV) { com.steplayer.app.ui.settings.SendToTvScreen(actions) }
        composable(
            Routes.CATEGORIES,
            arguments = listOf(navArgument("sourceId") { type = NavType.LongType }, navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            CategoriesScreen(entry.arguments!!.getLong("sourceId"), ContentType.valueOf(entry.arguments!!.getString("type")!!), actions)
        }
    }
}
