package com.steplayer.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.player.PlayerLauncher
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.ArtImage
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LiveBadge
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.PosterCard
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.SectionHeader
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.theme.Dimens
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import com.steplayer.shared.model.Channel
import com.steplayer.shared.search.ProgramStatus
import com.steplayer.shared.search.SearchHit
import com.steplayer.shared.search.SmartSearchResults
import com.steplayer.shared.util.normalizeForSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Immutable
data class SearchUi(val query: String = "", val loading: Boolean = false, val results: SmartSearchResults? = null)

class SearchViewModel(private val app: StePlayerApp) : ViewModel() {
    private val core = app.core
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    val recent: StateFlow<List<String>> = core.search.recentQueries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<SearchUi> =
        combine(_query.debounce(250).distinctUntilChanged(), core.sources.activeSource) { q, s -> q to s.id }
            .transformLatest { (q, id) ->
                // transformLatest cancels the previous search as soon as the user types again.
                if (q.trim().length < 2 || id < 0) emit(SearchUi(q, false, null))
                else {
                    emit(SearchUi(q, true, null))
                    emit(SearchUi(q, false, runCatching { core.search.search(id, q.trim()) }.getOrNull()))
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun onQuery(q: String) { _query.value = q }

    /** Called when the user submits the query or opens a result. */
    fun remember() {
        val q = _query.value.trim()
        if (q.length >= 2) viewModelScope.launch { core.search.rememberQuery(q) }
    }

    fun clearRecent() = viewModelScope.launch { core.search.clearRecentQueries() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(actions: AppActions) {
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    val posterWidth = if (isTv || LocalConfiguration.current.screenWidthDp >= 600) 140.dp else 112.dp

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Every result tap remembers the query first.
    val open = remember(vm, actions) {
        SearchOpen(
            channel = { ch -> vm.remember(); actions.playChannel(ch, ch.categoryId) },
            movie = { m -> vm.remember(); actions.openMovie(m) },
            series = { s -> vm.remember(); actions.openSeries(s) },
            program = { hit ->
                vm.remember()
                when (hit.status) {
                    ProgramStatus.REPLAY -> PlayerLauncher.catchup(context, hit.channel.sourceId, hit.channel.id, hit.program.startAt, hit.program.endAt, hit.program.title)
                    else -> actions.playChannel(hit.channel, hit.channel.categoryId)
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), actions::back)
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQuery,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.TextSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Rounded.Close, null, tint = Palette.TextSecondary) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.remember() }),
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Outline,
                    focusedContainerColor = Palette.Surface, unfocusedContainerColor = Palette.Surface, cursorColor = Palette.Accent,
                ),
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus),
            )
        }

        val r = state.results
        when {
            query.trim().length < 2 -> RecentSearches(recent, onPick = vm::onQuery, onClear = vm::clearRecent)
            state.loading && r == null -> LoadingBox()
            r == null || r.isEmpty -> EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_nothing, query.trim()))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                r.correctedQuery?.let { corrected ->
                    item(key = "corrected", contentType = "banner") { CorrectionBanner(corrected) }
                }
                if (r.topHits.isNotEmpty()) {
                    item(key = "top_h", contentType = "header") { SectionHeader(stringResource(R.string.search_top)) }
                    item(key = "top", contentType = "row") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(r.topHits, key = { i, it -> "${hitKey(it)}#$i" }, contentType = { _, it -> it::class }) { _, hit -> TopHitCard(hit, r.terms, posterWidth, open) }
                        }
                    }
                }
                programSection("live", R.string.search_live_now, r.liveNow, r.terms, open)
                programSection("up", R.string.search_upcoming, r.upcoming, r.terms, open)
                programSection("rep", R.string.search_replay, r.replay, r.terms, open)
                if (r.channels.isNotEmpty()) {
                    item(key = "ch_h", contentType = "header") { SectionHeader(stringResource(R.string.search_channels)) }
                    itemsIndexed(r.channels, key = { i, it -> "ch_${it.channel.id}#$i" }, contentType = { _, _ -> "channel" }) { _, hit ->
                        ChannelResult(hit.channel, r.terms) { open.channel(hit.channel) }
                    }
                }
                if (r.movies.isNotEmpty()) {
                    item(key = "mv_h", contentType = "header") { SectionHeader(stringResource(R.string.nav_movies)) }
                    item(key = "mv", contentType = "row") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(r.movies, key = { i, it -> "${it.movie.id}#$i" }, contentType = { _, _ -> "poster" }) { _, hit ->
                                val m = hit.movie
                                PosterCard(m.name, m.poster, subtitle = m.year?.toString(), rating = Format.rating(m.rating), width = posterWidth) { open.movie(m) }
                            }
                        }
                    }
                }
                if (r.series.isNotEmpty()) {
                    item(key = "se_h", contentType = "header") { SectionHeader(stringResource(R.string.nav_series)) }
                    item(key = "se", contentType = "row") {
                        LazyRow(contentPadding = PaddingValues(horizontal = Dimens.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(r.series, key = { i, it -> "${it.series.id}#$i" }, contentType = { _, _ -> "poster" }) { _, hit ->
                                val s = hit.series
                                PosterCard(
                                    s.name, s.cover,
                                    subtitle = if (hit.matchedInPlot) stringResource(R.string.search_in_plot) else s.year?.toString(),
                                    rating = Format.rating(s.rating), width = posterWidth,
                                ) { open.series(s) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Immutable
private class SearchOpen(
    val channel: (Channel) -> Unit,
    val movie: (com.steplayer.shared.model.Movie) -> Unit,
    val series: (com.steplayer.shared.model.Series) -> Unit,
    val program: (SearchHit.ProgramHit) -> Unit,
)

private fun hitKey(h: SearchHit): String = when (h) {
    is SearchHit.ChannelHit -> "c_${h.channel.id}"
    is SearchHit.MovieHit -> "m_${h.movie.id}"
    is SearchHit.SeriesHit -> "s_${h.series.id}"
    is SearchHit.ProgramHit -> "p_${h.channel.id}_${h.program.startAt}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) {
        EmptyState(Icons.Rounded.Search, stringResource(R.string.search_prompt))
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.search_clear),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Accent,
                modifier = Modifier.focusHighlight(RoundedCornerShape(50), 1.04f).clip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.forEach { q ->
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier.focusHighlight(shape, 1.04f).clip(shape).background(Palette.SurfaceElevated).clickable { onPick(q) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(q, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun CorrectionBanner(corrected: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.Gutter, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(Palette.Accent.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoFixHigh, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.search_results_for, corrected), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.search_did_you_mean, corrected), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

private fun LazyListScope.programSection(
    key: String, title: Int, hits: List<SearchHit.ProgramHit>, terms: List<String>, open: SearchOpen,
) {
    if (hits.isEmpty()) return
    item(key = "${key}_h", contentType = "header") { SectionHeader(stringResource(title)) }
    // EPG rows have no primary key: the same programme can appear twice, so keys carry the index.
    itemsIndexed(hits, key = { i, it -> "${key}_${it.channel.id}_${it.program.startAt}#$i" }, contentType = { _, _ -> "program" }) { _, hit ->
        ProgramResult(hit, terms) { open.program(hit) }
    }
}

@Composable
private fun ProgramResult(hit: SearchHit.ProgramHit, terms: List<String>, onClick: () -> Unit) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .focusHighlight(shape, 1.02f)
            .clip(shape)
            .background(Palette.Surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceElevated), contentAlignment = Alignment.Center) {
            ArtImage(hit.channel.logo, hit.channel.name, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hit.status == ProgramStatus.LIVE) {
                    LiveBadge()
                    Spacer(Modifier.width(8.dp))
                }
                Text(highlight(hit.program.title, terms), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            val whenLabel = when (hit.status) {
                ProgramStatus.LIVE -> "${Format.clock(hit.program.startAt)} – ${Format.clock(hit.program.endAt)}"
                else -> dayLabel(context, hit.program.startAt)
            }
            val extra = if (hit.otherAirings > 0) "  ·  " + stringResource(R.string.search_other_airings, hit.otherAirings) else ""
            Text("${hit.channel.name}  ·  $whenLabel$extra", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit.status == ProgramStatus.LIVE) {
                Spacer(Modifier.height(6.dp))
                ProgressBar(hit.program.progress(now), Modifier.fillMaxWidth(0.9f), height = 3.dp)
            }
        }
    }
}

@Composable
private fun ChannelResult(channel: Channel, terms: List<String>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
            .focusHighlight(shape, 1.02f).clip(shape).background(Palette.Surface).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceElevated), contentAlignment = Alignment.Center) {
            ArtImage(channel.logo, channel.name, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.width(12.dp))
        Text(highlight(channel.name, terms), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Mixed card for the "best results" row: posters for VOD, 16:9 tiles for channels/programmes. */
@Composable
private fun TopHitCard(hit: SearchHit, terms: List<String>, posterWidth: androidx.compose.ui.unit.Dp, open: SearchOpen) {
    when (hit) {
        is SearchHit.MovieHit -> PosterCard(hit.movie.name, hit.movie.poster, subtitle = stringResource(R.string.nav_movies), width = posterWidth) { open.movie(hit.movie) }
        is SearchHit.SeriesHit -> PosterCard(hit.series.name, hit.series.cover, subtitle = stringResource(R.string.nav_series), width = posterWidth) { open.series(hit.series) }
        is SearchHit.ChannelHit -> TileCard(hit.channel.logo, hit.channel.name, highlight(hit.channel.name, terms), stringResource(R.string.search_channels), false, posterWidth) { open.channel(hit.channel) }
        is SearchHit.ProgramHit -> {
            val context = LocalContext.current
            val sub = when (hit.status) {
                ProgramStatus.LIVE -> hit.channel.name
                ProgramStatus.UPCOMING -> "${hit.channel.name} · ${dayLabel(context, hit.program.startAt)}"
                ProgramStatus.REPLAY -> "${stringResource(R.string.search_replay)} · ${hit.channel.name}"
            }
            TileCard(hit.channel.logo, hit.program.title, highlight(hit.program.title, terms), sub, hit.status == ProgramStatus.LIVE, posterWidth) { open.program(hit) }
        }
    }
}

@Composable
private fun TileCard(logo: String?, rawTitle: String, title: AnnotatedString, subtitle: String, live: Boolean, posterWidth: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(posterWidth * 1.6f)) {
        Box(
            Modifier.fillMaxWidth().height(posterWidth * 1.5f).focusHighlight(shape).clip(shape).background(Palette.Surface).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ArtImage(logo, rawTitle, Modifier.fillMaxSize().padding(28.dp), contentScale = ContentScale.Fit)
            if (live) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Oggi 20:45", "Domani 15:00", "Sab 21:00". */
private fun dayLabel(context: android.content.Context, epochMs: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, 1)
    val tomorrow = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = epochMs
    val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    val time = Format.clock(epochMs)
    return when (day) {
        today -> "${context.getString(R.string.today)} $time"
        tomorrow -> "${context.getString(R.string.tomorrow)} $time"
        else -> SimpleDateFormat("EEE", Locale.ITALY).format(Date(epochMs)).replaceFirstChar { it.uppercase() }.trimEnd('.') + " $time"
    }
}

/** Bolds words whose accent-free form starts with one of the matched search terms. */
private fun highlight(text: String, terms: List<String>): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetterOrDigit()) { append(text[i]); i++; continue }
            var j = i
            while (j < text.length && text[j].isLetterOrDigit()) j++
            val word = text.substring(i, j)
            val norm = normalizeForSearch(word)
            val match = terms.firstOrNull { it.isNotEmpty() && norm.startsWith(it) }
            if (match != null) {
                val len = minOf(match.length, word.length)
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = Palette.Accent2)) { append(word.substring(0, len)) }
                append(word.substring(len))
            } else append(word)
            i = j
        }
    }
}
