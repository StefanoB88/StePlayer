package com.steplayer.app.ui

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.steplayer.app.R
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.downloads.DownloadsScreen
import com.steplayer.app.ui.home.HomeScreen
import com.steplayer.app.ui.live.LiveScreen
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.vod.MoviesScreen
import com.steplayer.app.ui.vod.SeriesScreen

enum class Tab(val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Rounded.Home),
    LIVE(R.string.nav_live, Icons.Rounded.LiveTv),
    MOVIES(R.string.nav_movies, Icons.Rounded.Movie),
    SERIES(R.string.nav_series, Icons.Rounded.VideoLibrary),
    DOWNLOADS(R.string.nav_downloads, Icons.Rounded.Download),
}

/** Bottom bar on phones, side rail on tablets and TV. Tab state is preserved across switches. */
@Composable
fun MainShell(actions: AppActions) {
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
    val tabs = if (isTv) Tab.entries.filter { it != Tab.DOWNLOADS } else Tab.entries
    var selected by rememberSaveable { mutableTabState() }
    val holder = rememberSaveableStateHolder()

    val content: @Composable () -> Unit = {
        Crossfade(selected, animationSpec = tween(180), label = "tab") { tab ->
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.HOME -> HomeScreen(actions, onSwitchTab = { selected = it })
                    Tab.LIVE -> LiveScreen(actions)
                    Tab.MOVIES -> MoviesScreen(actions)
                    Tab.SERIES -> SeriesScreen(actions)
                    Tab.DOWNLOADS -> DownloadsScreen(actions)
                }
            }
        }
    }

    if (wide) {
        // TV remote convention: Back from the content goes to the rail (on the current tab), Back on the rail goes
        // to Home, Back on Home's rail leaves the app. Without this the first Back closed the app.
        val railFocus = remember { tabs.associateWith { FocusRequester() } }
        var railFocused by remember { mutableStateOf(false) }
        BackHandler(enabled = isTv && (!railFocused || selected != Tab.HOME)) {
            if (!railFocused) {
                runCatching { railFocus.getValue(selected).requestFocus() }
            } else {
                selected = Tab.HOME
                runCatching { railFocus.getValue(Tab.HOME).requestFocus() }
            }
        }
        Row(Modifier.fillMaxSize()) {
            NavRail(
                tabs, selected, onSelect = { selected = it }, actions = actions,
                focusRequesters = railFocus,
                modifier = Modifier.onFocusChanged { railFocused = it.hasFocus },
            )
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            BottomBar(tabs, selected) { selected = it }
        }
    }
}

private fun mutableTabState() = androidx.compose.runtime.mutableStateOf(Tab.HOME)

@Composable
private fun BottomBar(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .navigationBarsPadding()
            .height(64.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSel = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSel) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(tab.icon, null, tint = if (isSel) Palette.Accent else Palette.TextSecondary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) Palette.TextPrimary else Palette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun NavRail(
    tabs: List<Tab>,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    actions: AppActions,
    focusRequesters: Map<Tab, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(92.dp)
            .background(Palette.Surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource(R.drawable.ic_brand), null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        RailItem(Icons.Rounded.Search, stringResource(R.string.nav_search), false) { actions.openSearch() }
        tabs.forEach { tab ->
            RailItem(tab.icon, stringResource(tab.label), tab == selected, Modifier.focusRequester(focusRequesters.getValue(tab))) { onSelect(tab) }
        }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Rounded.Settings, stringResource(R.string.nav_settings), false) { actions.openSettings() }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(76.dp)
            .focusHighlight(shape, 1.05f)
            .clip(shape)
            .background(if (selected) Palette.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) Palette.Accent else Palette.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) Palette.TextPrimary else Palette.TextSecondary, maxLines = 1)
    }
}
