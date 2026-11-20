package com.steplayer.app.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.CategoryChips
import com.steplayer.app.ui.components.ChipItem
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.PosterCard
import com.steplayer.app.ui.components.RoundIconButton
import com.steplayer.app.ui.components.posterMinSize
import com.steplayer.app.ui.nav.AppActions
import com.steplayer.app.ui.search.CorrectionBanner
import com.steplayer.app.ui.search.SectionSearch
import com.steplayer.app.ui.search.SectionSearchBody
import com.steplayer.app.ui.search.SectionSearchField
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.app.util.Format
import com.steplayer.shared.data.Categories
import com.steplayer.shared.model.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Grid-ready item shared by movies and series. */
@Immutable
data class GridItem(val sourceId: Long, val id: String, val title: String, val image: String?, val subtitle: String?, val rating: String?)

@Immutable
data class GridState(
    val sourceId: Long = -1,
    val chips: List<ChipItem> = emptyList(),
    val selected: String? = null,
    val items: List<GridItem>? = null,
)

class VodGridViewModel(app: StePlayerApp, private val type: ContentType, labels: Triple<String, String, String>) : ViewModel() {
    private val core = app.core
    private val selected = MutableStateFlow<String?>(null)
    private val source = core.sources.activeSource.map { it.id }.distinctUntilChanged()

    private val chips: Flow<List<ChipItem>> = source.flatMapLatest { id ->
        if (id < 0) flowOf(emptyList())
        else core.catalog.categories(id, type).map { cats ->
            listOf(
                ChipItem(Categories.RECENT, labels.first),
                ChipItem(Categories.FAVORITES, labels.second),
                ChipItem(Categories.ALL, labels.third),
            ) + cats.map { ChipItem(it.id, it.name) }
        }
    }

    private val effective = combine(source, chips, selected) { id, c, sel ->
        id to (sel?.takeIf { s -> c.any { it.id == s } } ?: c.firstOrNull()?.id)
    }.distinctUntilChanged()

    private val items: Flow<List<GridItem>?> = effective.flatMapLatest { (id, cat) ->
        if (id < 0 || cat == null) flowOf(emptyList())
        else when (type) {
            ContentType.SERIES -> core.catalog.series(id, cat, if (cat == Categories.RECENT) 120 else 5000).map { l ->
                l.map { GridItem(it.sourceId, it.id, it.name, it.cover, it.year?.toString(), Format.rating(it.rating)) }
            }
            else -> core.catalog.movies(id, cat, if (cat == Categories.RECENT) 120 else 5000).map { l ->
                l.map { GridItem(it.sourceId, it.id, it.name, it.poster, it.year?.toString(), Format.rating(it.rating)) }
            }
        }.onStart<List<GridItem>?> { emit(null) }
    }

    // Up to 5,000 posters per category: map/compare the lists off the main thread.
    val state: StateFlow<GridState> = combine(source, chips, effective, items) { id, c, eff, it ->
        GridState(id, c, eff.second, it)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GridState())

    fun select(id: String) { selected.value = id }

    /** Search box of the section: only movies (or only series) of the smart search. */
    val search = SectionSearch(core, viewModelScope)
}

@Composable
fun MoviesScreen(actions: AppActions) =
    VodGridScreen(ContentType.MOVIE, stringResource(R.string.nav_movies), stringResource(R.string.search_section_movies), Icons.Rounded.Movie, stringResource(R.string.empty_movies), actions) {
        actions.openMovie(it.sourceId, it.id)
    }

@Composable
fun SeriesScreen(actions: AppActions) =
    VodGridScreen(ContentType.SERIES, stringResource(R.string.nav_series), stringResource(R.string.search_section_series), Icons.Rounded.VideoLibrary, stringResource(R.string.empty_series), actions) {
        actions.openSeries(it.sourceId, it.id)
    }

@Composable
private fun VodGridScreen(
    type: ContentType,
    title: String,
    searchHint: String,
    emptyIcon: ImageVector,
    emptyText: String,
    actions: AppActions,
    onOpen: (GridItem) -> Unit,
) {
    val labels = Triple(stringResource(R.string.cat_recent), stringResource(R.string.cat_favorites), stringResource(R.string.cat_all))
    val vm = appViewModel(key = "grid_${type.name}") { VodGridViewModel(it, type, labels) }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.search.query.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        AppTopBar(title, large = true) {
            if (wide) SectionSearchField(query, vm.search::onQuery, searchHint, Modifier.width(if (isTv) 340.dp else 260.dp))
            if (!isTv) {
                RoundIconButton(Icons.Rounded.Search, stringResource(R.string.nav_search), actions::openSearch)
                RoundIconButton(Icons.Rounded.Settings, stringResource(R.string.nav_settings), actions::openSettings)
            }
        }
        if (!wide) SectionSearchField(query, vm.search::onQuery, searchHint, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        if (query.isNotEmpty()) {
            val ui by vm.search.state.collectAsStateWithLifecycle()
            val inPlot = stringResource(R.string.search_in_plot)
            SectionSearchBody(query, ui, isEmpty = { if (type == ContentType.SERIES) it.series.isEmpty() else it.movies.isEmpty() }) { r ->
                val hits = remember(r) {
                    if (type == ContentType.SERIES) r.series.map {
                        val s = it.series
                        GridItem(s.sourceId, s.id, s.name, s.cover, if (it.matchedInPlot) inPlot else s.year?.toString(), Format.rating(s.rating))
                    } else r.movies.map {
                        val m = it.movie
                        GridItem(m.sourceId, m.id, m.name, m.poster, m.year?.toString(), Format.rating(m.rating))
                    }
                }
                PosterGrid(hits, searchResults = true, corrected = r.correctedQuery) { vm.search.remember(); onOpen(it) }
            }
            return@Column
        }
        CategoryChips(state.chips, state.selected, vm::select, Modifier.padding(bottom = 8.dp))
        val items = state.items
        when {
            items == null -> LoadingBox()
            items.isEmpty() -> EmptyState(emptyIcon, emptyText)
            else -> PosterGrid(items, onOpen = onOpen)
        }
    }
}

@Composable
private fun PosterGrid(items: List<GridItem>, searchResults: Boolean = false, corrected: String? = null, onOpen: (GridItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(posterMinSize()),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (corrected != null) item(key = "corrected", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") { CorrectionBanner(corrected) }
        // Search hits may repeat an id (same title in several categories): keys carry the index.
        itemsIndexed(items, key = { i, it -> if (searchResults) "${it.id}#$i" else it.id }, contentType = { _, _ -> "poster" }) { _, item ->
            PosterCard(item.title, item.image, subtitle = item.subtitle, rating = item.rating) { onOpen(item) }
        }
    }
}
