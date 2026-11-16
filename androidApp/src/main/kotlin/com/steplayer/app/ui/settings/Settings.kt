package com.steplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.BuildConfig
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.onboarding.errorMessage
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.ui.toast
import com.steplayer.app.util.Format
import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.model.Category
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.model.SyncState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(val app: StePlayerApp) : ViewModel() {
    private val core = app.core
    val sources: StateFlow<List<Source>> = core.sources.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val active: StateFlow<Source> = core.sources.activeSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceRepository.NO_SOURCE)
    val sync: StateFlow<SyncState> = core.sync.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncState.Idle)
    val softwareDecoder = app.prefs.softwareDecoder

    fun setActive(id: Long) = viewModelScope.launch { core.sources.setActive(id) }

    fun syncNow(id: Long, onResult: (Throwable?) -> Unit) = app.appScope.launch {
        val r = runCatching { core.sync.sync(id) }.exceptionOrNull()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(r) }
    }

    fun refreshEpg(source: Source, onResult: (Throwable?) -> Unit) = app.appScope.launch {
        val r = runCatching { core.epg.refresh(source) }.exceptionOrNull()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(r) }
    }

    fun delete(id: Long, onEmpty: () -> Unit) = viewModelScope.launch {
        core.sources.delete(id)
        if (core.sources.current() == null) onEmpty()
    }

    fun setSoftwareDecoder(v: Boolean) = app.prefs.setSoftwareDecoder(v)

    val expiryEnabled: StateFlow<Boolean> = core.reminders.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setExpiryEnabled(v: Boolean) = viewModelScope.launch { core.reminders.setEnabled(v) }

    fun updatePlayer(t: (com.steplayer.shared.settings.PlayerSettings) -> com.steplayer.shared.settings.PlayerSettings) =
        viewModelScope.launch { core.playerPreferences.update(t) }
}

@Composable
fun SettingsScreen(actions: AppActions) {
    val vm = appViewModel { SettingsViewModel(it) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val sw by vm.softwareDecoder.collectAsStateWithLifecycle()
    val expiry by vm.expiryEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<Source?>(null) }
    var epgBusy by remember { mutableStateOf(false) }
    val isTv = com.steplayer.app.ui.theme.LocalIsTv.current
    val offerPhone = isTv || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    var frameRate by remember { mutableStateOf(vm.app.prefs.frameRateMode) }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(stringResource(R.string.nav_settings), onBack = actions::back, large = true)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Section(stringResource(R.string.settings_lists)) {
                        sources.forEach { s ->
                            SourceRow(
                                s, isActive = s.id == active.id,
                                onUse = { vm.setActive(s.id) },
                                onEdit = { actions.editSource(s.id) },
                                onDelete = { deleting = s },
                            )
                        }
                        SettingRow(Icons.Rounded.Add, stringResource(R.string.add_list), onClick = actions::addSource)
                        if (!isTv && sources.isNotEmpty()) {
                            SettingRow(Icons.Rounded.Tv, stringResource(R.string.send_tv_title), subtitle = stringResource(R.string.send_tv_desc), onClick = actions::openSendToTv)
                        }
                        if (offerPhone) SettingRow(Icons.Rounded.QrCode2, stringResource(R.string.pair_title), subtitle = stringResource(R.string.pair_use_phone), onClick = actions::addSourceWithPhone)
                    }
                }
                if (active.id >= 0) {
                    item {
                        Section(active.name) {
                            val running = sync is SyncState.Running
                            SettingRow(
                                Icons.Rounded.Sync, stringResource(R.string.settings_sync_now),
                                subtitle = if (running) stringResource(R.string.syncing)
                                else stringResource(R.string.settings_last_sync, active.lastSyncAt?.let { Format.dayTime(it) } ?: stringResource(R.string.settings_never)),
                                busy = running,
                                onClick = {
                                    if (!running) vm.syncNow(active.id) { e ->
                                        context.toast(if (e == null) context.getString(R.string.sync_done) else errorMessage(context, e))
                                    }
                                },
                            )
                            SettingRow(
                                Icons.Rounded.CalendarMonth, stringResource(R.string.settings_refresh_epg),
                                subtitle = active.lastEpgSyncAt?.let { stringResource(R.string.settings_last_sync, Format.dayTime(it)) },
                                busy = epgBusy,
                                onClick = {
                                    if (!epgBusy) {
                                        epgBusy = true
                                        vm.refreshEpg(active) { e ->
                                            epgBusy = false
                                            context.toast(if (e == null) context.getString(R.string.epg_refreshed) else errorMessage(context, e))
                                        }
                                    }
                                },
                            )
                            if (active.type == SourceType.XTREAM) {
                                SettingRow(
                                    Icons.Rounded.Info, stringResource(R.string.settings_account),
                                    subtitle = listOfNotNull(
                                        stringResource(R.string.settings_expires, active.expiresAt?.let { Format.date(it) } ?: stringResource(R.string.settings_unlimited)),
                                        active.maxConnections?.let { stringResource(R.string.settings_max_conn, it) },
                                    ).joinToString("  ·  "),
                                )
                            }
                            SettingRow(Icons.Rounded.LiveTv, stringResource(R.string.settings_categories) + " · " + stringResource(R.string.nav_live),
                                subtitle = stringResource(R.string.settings_categories_desc), onClick = { actions.openCategories(active.id, ContentType.LIVE) })
                            SettingRow(Icons.Rounded.Movie, stringResource(R.string.settings_categories) + " · " + stringResource(R.string.nav_movies),
                                onClick = { actions.openCategories(active.id, ContentType.MOVIE) })
                            SettingRow(Icons.Rounded.VideoLibrary, stringResource(R.string.settings_categories) + " · " + stringResource(R.string.nav_series),
                                onClick = { actions.openCategories(active.id, ContentType.SERIES) })
                        }
                    }
                }
                item {
                    Section(stringResource(R.string.settings_player)) {
                        Text(stringResource(R.string.settings_decoder), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        RadioRow(stringResource(R.string.decoder_hw), !sw) { vm.setSoftwareDecoder(false) }
                        RadioRow(stringResource(R.string.decoder_sw), sw) { vm.setSoftwareDecoder(true) }
                        if (isTv) {
                            Text(stringResource(R.string.settings_frame_rate), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            listOf(R.string.frame_rate_off, R.string.frame_rate_seamless, R.string.frame_rate_always).forEachIndexed { i, label ->
                                RadioRow(stringResource(label), frameRate == i) { frameRate = i; vm.app.prefs.frameRateMode = i }
                            }
                        }
                    }
                }
                item {
                    val ps by vm.app.core.playerPreferences.settings.collectAsStateWithLifecycle(com.steplayer.shared.settings.PlayerSettings())
                    val never = stringResource(R.string.never)
                    Section(stringResource(R.string.settings_playback)) {
                        ChoiceRow(
                            stringResource(R.string.settings_still_watching),
                            listOf(0 to never) + listOf(2, 3, 4, 6).map { it to stringResource(R.string.hours_n, it) },
                            ps.stillWatchingAfterHours,
                        ) { h -> vm.updatePlayer { it.copy(stillWatchingAfterHours = h) } }
                        ChoiceRow(
                            stringResource(R.string.settings_buffer),
                            listOf(com.steplayer.shared.settings.BufferMode.LARGE, com.steplayer.shared.settings.BufferMode.AUTO, com.steplayer.shared.settings.BufferMode.NORMAL).map { it to bufferLabel(it) },
                            ps.bufferMode,
                        ) { m -> vm.updatePlayer { it.copy(bufferMode = m) } }
                        val deviceLabel = stringResource(R.string.lang_device)
                        ChoiceRow(
                            stringResource(R.string.settings_audio_language),
                            PreferredLanguages.map { (c, n) -> c to (if (c == null) deviceLabel else n) },
                            ps.preferredAudioLanguages.firstOrNull(),
                        ) { c -> vm.updatePlayer { it.copy(preferredAudioLanguages = listOfNotNull(c)) } }
                        SwitchRow(Icons.Rounded.Info, stringResource(R.string.settings_stats_overlay), stringResource(R.string.stats), ps.showStatsOverlay) { v ->
                            vm.updatePlayer { it.copy(showStatsOverlay = v) }
                        }
                        SettingRow(Icons.Rounded.ClosedCaption, stringResource(R.string.settings_subtitles), onClick = actions::openSubtitleSettings)
                        if (active.id >= 0) {
                            SettingRow(Icons.Rounded.NetworkCheck, stringResource(R.string.conn_test), subtitle = stringResource(R.string.conn_test_desc), onClick = { actions.openConnectionTest(active.id) })
                        }
                    }
                }
                item {
                    Section(stringResource(R.string.settings_expiry)) {
                        SwitchRow(Icons.Rounded.NotificationsActive, stringResource(R.string.settings_expiry), stringResource(R.string.settings_expiry_desc), expiry, vm::setExpiryEnabled)
                    }
                }
                item {
                    Section(stringResource(R.string.settings_legal)) {
                        SettingRow(Icons.Rounded.Gavel, stringResource(R.string.disclaimer))
                        SettingRow(Icons.Rounded.Lock, stringResource(R.string.settings_privacy), subtitle = stringResource(R.string.legal_privacy))
                        SettingRow(Icons.Rounded.Info, stringResource(R.string.legal_affiliation))
                    }
                }
                item {
                    Section(stringResource(R.string.settings_about)) {
                        SettingRow(Icons.Rounded.Info, "StePlayer", subtitle = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
                        SettingRow(Icons.Rounded.Gavel, stringResource(R.string.settings_licenses), subtitle = stringResource(R.string.settings_licenses_desc), onClick = actions::openLicenses)
                    }
                }
            }
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.settings_delete_q, s.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.delete(s.id) { actions.goToOnboarding() }
                }) { Text(stringResource(R.string.delete), color = Palette.Live) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            containerColor = Palette.SurfaceElevated,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(vertical = 6.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    busy: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .then(if (onClick != null) Modifier.focusHighlight(shape, 1.01f).clip(shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
        }
        if (busy) CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).focusHighlight(shape, 1.01f).clip(shape)
            .clickable { onChange(!checked) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).focusHighlight(shape, 1.01f).clip(shape).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun SourceRow(s: Source, isActive: Boolean, onUse: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge)
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.CheckCircle, stringResource(R.string.settings_active), tint = Palette.Success, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                (if (s.type == SourceType.XTREAM) "Server · " else "M3U · ") + s.url.substringAfter("://").substringBefore('/'),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            )
        }
        if (!isActive) SecondaryButton(stringResource(R.string.settings_use), onClick = onUse)
        Spacer(Modifier.width(4.dp))
        com.steplayer.app.ui.components.RoundIconButton(Icons.Rounded.Edit, stringResource(R.string.edit), onEdit, tint = Palette.TextSecondary)
        com.steplayer.app.ui.components.RoundIconButton(Icons.Rounded.Delete, stringResource(R.string.delete), onDelete, tint = Palette.TextSecondary)
    }
}

// ---------------------------------------------------------------- Category visibility

class CategoriesViewModel(private val app: StePlayerApp, private val sourceId: Long, private val type: ContentType) : ViewModel() {
    val categories: StateFlow<List<Category>> = app.core.catalog.allCategories(sourceId, type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(c: Category) = viewModelScope.launch { app.core.catalog.setCategoryHidden(sourceId, type, c, !c.hidden) }
}

@Composable
fun CategoriesScreen(sourceId: Long, type: ContentType, actions: AppActions) {
    val vm = appViewModel(key = "cats_${sourceId}_$type") { CategoriesViewModel(it, sourceId, type) }
    val cats by vm.categories.collectAsStateWithLifecycle()
    val title = stringResource(
        when (type) {
            ContentType.LIVE -> R.string.nav_live
            ContentType.MOVIE -> R.string.nav_movies
            else -> R.string.nav_series
        },
    )
    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar("${stringResource(R.string.settings_categories)} · $title", onBack = actions::back)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(cats, key = { it.id }) { c ->
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusHighlight(shape, 1.01f)
                        .clip(shape)
                        .background(Palette.Surface)
                        .clickable { vm.toggle(c) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = !c.hidden, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
                    )
                }
            }
        }
    }
}
