package com.steplayer.app.ui.nav

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.navigation.NavHostController
import com.steplayer.app.player.PlayerLauncher
import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.Series
import com.steplayer.shared.model.WatchProgress

object Routes {
    const val ONBOARDING = "onboarding"
    const val ADD_SOURCE = "add_source?editId={editId}&first={first}&phone={phone}"
    const val SYNC = "sync/{sourceId}"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val MOVIE = "movie/{sourceId}/{id}"
    const val SERIES = "series/{sourceId}/{id}"
    const val GUIDE = "guide/{sourceId}/{categoryId}"
    const val CATEGORIES = "categories/{sourceId}/{type}"
    const val CONNTEST = "conntest/{sourceId}"
    const val SUBTITLES = "subtitles"
    const val SEND_TV = "send_tv"
}

/** All navigation entry points, so screens don't build routes by hand. */
@Stable
class AppActions(private val nav: NavHostController, private val context: Context) {
    private fun enc(s: String) = Uri.encode(s)

    fun back() { nav.popBackStack() }
    fun openSearch() = nav.navigate(Routes.SEARCH)
    fun openSettings() = nav.navigate(Routes.SETTINGS)
    fun openMovie(sourceId: Long, id: String) = nav.navigate("movie/$sourceId/${enc(id)}")
    fun openSeries(sourceId: Long, id: String) = nav.navigate("series/$sourceId/${enc(id)}")
    fun openMovie(m: Movie) = openMovie(m.sourceId, m.id)
    fun openSeries(s: Series) = openSeries(s.sourceId, s.id)
    fun openGuide(sourceId: Long, categoryId: String) = nav.navigate("guide/$sourceId/${enc(categoryId)}")
    fun openCategories(sourceId: Long, type: ContentType) = nav.navigate("categories/$sourceId/${type.name}")
    fun openConnectionTest(sourceId: Long) = nav.navigate("conntest/$sourceId")
    fun openSubtitleSettings() = nav.navigate(Routes.SUBTITLES)
    fun openSendToTv() = nav.navigate(Routes.SEND_TV)
    fun addSource() = nav.navigate("add_source?editId=-1&first=false")
    fun addSourceWithPhone() = nav.navigate("add_source?editId=-1&first=false&phone=true")
    fun editSource(id: Long) = nav.navigate("add_source?editId=$id&first=false")

    fun playChannel(channel: Channel, categoryId: String?) =
        PlayerLauncher.live(context, channel.sourceId, channel.id, categoryId)

    fun playMovie(m: Movie, startMs: Long = -1) = PlayerLauncher.movie(context, m.sourceId, m.id, startMs)

    /** Opens an item from "continue watching" / "recent channels". */
    fun playProgress(p: WatchProgress) = when (p.type) {
        ContentType.LIVE -> PlayerLauncher.live(context, p.sourceId, p.itemId, null)
        ContentType.MOVIE -> PlayerLauncher.movie(context, p.sourceId, p.itemId, -1)
        ContentType.EPISODE -> PlayerLauncher.episode(context, p.sourceId, p.parentId.orEmpty(), p.itemId, -1)
        ContentType.SERIES -> openSeries(p.sourceId, p.itemId)
    }

    fun goToMainClearingStack() = nav.navigate(Routes.MAIN) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }

    fun goToSync(sourceId: Long) = nav.navigate("sync/$sourceId") {
        popUpTo(0) { inclusive = true }
    }

    fun goToOnboarding() = nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
}
