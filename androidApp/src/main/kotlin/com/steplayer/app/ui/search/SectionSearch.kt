package com.steplayer.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.steplayer.app.R
import com.steplayer.app.ui.components.EmptyState
import com.steplayer.app.ui.components.LoadingBox
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.theme.LocalIsTv
import com.steplayer.app.ui.theme.Palette
import com.steplayer.shared.StePlayerCore
import com.steplayer.shared.search.SmartSearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Search box of one section (Live, Film, Serie): the same smart search as the "Cerca" screen,
 * each screen shows only its own kind of results. Owned by the section's ViewModel.
 */
class SectionSearch(private val core: StePlayerCore, private val scope: CoroutineScope) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<SearchUi> =
        // Clearing the field restores the list at once; typing waits for a pause.
        combine(_query.debounce { if (it.isBlank()) 0L else 300L }.distinctUntilChanged(), core.sources.activeSource) { q, s -> q to s.id }
            .transformLatest { (q, id) ->
                if (q.trim().length < 2 || id < 0) emit(SearchUi(q, false, null))
                else {
                    emit(SearchUi(q, true, null))
                    emit(SearchUi(q, false, runCatching { core.search.search(id, q.trim()) }.getOrNull()))
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    fun onQuery(q: String) { _query.value = q }

    fun clear() { _query.value = "" }

    /** Called when the user opens a result (same as the global search). */
    fun remember() {
        val q = _query.value.trim()
        if (q.length >= 2) scope.launch { core.search.rememberQuery(q) }
    }
}

/**
 * Compact search field for a section header.
 *
 * Phone/tablet: a plain text field. TV: a focusable pill reachable with the D-pad that never takes
 * the screen's initial focus and doesn't pop the keyboard while focus passes over it; OK starts
 * editing (system keyboard), "Cerca"/Done on the keyboard goes back to the pill.
 * Back while a query is active clears it first (before the shell's Back handling).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }
    val pillFocus = remember { FocusRequester() }
    // TV only: the inner text field is focusable only while editing.
    var editing by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }

    // While the keyboard is up, Back only closes it (the system does that); the next Back clears the query.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = query.isNotEmpty() && !imeVisible) {
        onQuery("")
        // TV: the focused result disappears with the results; land on the field instead of nowhere.
        if (isTv) runCatching { pillFocus.requestFocus() }
    }

    LaunchedEffect(editing) {
        if (isTv && editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }

    val shape = RoundedCornerShape(50)
    val done = {
        keyboard?.hide()
        if (isTv) {
            editing = false
            runCatching { pillFocus.requestFocus() }
        } else focusManager.clearFocus()
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .then(if (isTv) Modifier.focusRequester(pillFocus).focusHighlight(shape, 1.04f) else Modifier)
            .clip(shape)
            .background(Palette.SurfaceElevated)
            // Phone: accent outline while typing (TV draws its own focus highlight).
            .then(if (fieldFocused && !isTv) Modifier.border(1.5.dp, Palette.Accent, shape) else Modifier)
            // TV: OK starts editing. Phone: a tap anywhere on the pill (icon, padding) focuses the text field.
            .clickable { if (isTv) editing = true else runCatching { fieldFocus.requestFocus() } }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (fieldFocused) Palette.Accent else Palette.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { done() }, onDone = { done() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .then(if (isTv) Modifier.focusProperties { canFocus = editing } else Modifier)
                    // TV: a focused text field keeps Up/Down once the keyboard is closed; leave editing and move on,
                    // so the remote goes from the field straight to the results.
                    .onPreviewKeyEvent { e ->
                        val dir = when (e.key) {
                            Key.DirectionDown -> FocusDirection.Down
                            Key.DirectionUp -> FocusDirection.Up
                            else -> null
                        }
                        if (!isTv || dir == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        keyboard?.hide()
                        editing = false
                        runCatching { pillFocus.requestFocus() }
                        focusManager.moveFocus(dir)
                        true
                    }
                    .onFocusChanged {
                        fieldFocused = it.isFocused
                        if (isTv && !it.isFocused && editing) editing = false
                    },
            )
        }
        if (query.isNotEmpty()) {
            // Phone: tap target to clear. TV: Back clears (a second focus stop inside the pill would be confusing).
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(if (isTv) Modifier.focusProperties { canFocus = false } else Modifier)
                    .clickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.search_clear), tint = Palette.TextSecondary, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

/**
 * Body of a section while its search field is not empty: prompt under 2 characters, spinner while
 * searching, the Italian "nothing found" message, else [content] with the results.
 * [isEmpty] tells whether the results have anything for this section.
 */
@Composable
fun SectionSearchBody(
    query: String,
    state: SearchUi,
    isEmpty: (SmartSearchResults) -> Boolean,
    content: @Composable (SmartSearchResults) -> Unit,
) {
    val q = query.trim()
    val r = state.results
    // Results of the previous query stay up while the next one runs (no flashing on every key).
    val stale = state.query.trim() != q
    when {
        q.length < 2 -> EmptyState(Icons.Rounded.Search, stringResource(R.string.search_prompt))
        r == null -> if (state.loading || stale) LoadingBox() else EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_nothing, q))
        isEmpty(r) -> if (stale) LoadingBox() else EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.search_nothing, q))
        else -> content(r)
    }
}
