package com.steplayer.app.ui.onboarding

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.steplayer.app.R
import com.steplayer.app.StePlayerApp
import com.steplayer.app.ui.appViewModel
import com.steplayer.app.ui.components.AppTopBar
import com.steplayer.app.ui.components.PrimaryButton
import com.steplayer.app.ui.components.ProgressBar
import com.steplayer.app.ui.components.SecondaryButton
import com.steplayer.app.ui.components.focusHighlight
import com.steplayer.app.ui.rememberApp
import com.steplayer.app.ui.theme.Palette
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.model.SyncState
import com.steplayer.shared.model.SyncStep
import com.steplayer.shared.xtream.XtreamException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

// ---------------------------------------------------------------- Welcome

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val app = rememberApp()
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        // Soft accent glow
        Box(
            Modifier
                .size(420.dp)
                .align(Alignment.TopCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.35f), Color.Transparent)),
                ),
        )
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.ic_brand), null, modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            InfoCard(Icons.Rounded.Gavel, stringResource(R.string.disclaimer_gate))
            Spacer(Modifier.height(12.dp))
            InfoCard(Icons.Rounded.Lock, stringResource(R.string.privacy_note))
            Spacer(Modifier.height(20.dp))
            var confirmed by rememberSaveable { mutableStateOf(app.prefs.disclaimerAccepted) }
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusHighlight(shape, 1.02f)
                    .clip(shape)
                    .clickable { confirmed = !confirmed }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = confirmed, onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, uncheckedColor = Palette.TextSecondary, checkmarkColor = Palette.OnAccent),
                )
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.disclaimer_checkbox), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                stringResource(R.string.disclaimer_accept),
                enabled = confirmed,
                onClick = {
                    app.prefs.disclaimerAccepted = true
                    onContinue()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun InfoCard(icon: ImageVector, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = Palette.Accent2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
    }
}

// ---------------------------------------------------------------- Add / edit source

@Immutable
data class AddSourceForm(
    val type: SourceType = SourceType.XTREAM,
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val editing: Boolean = false,
)

class AddSourceViewModel(private val app: StePlayerApp, private val editId: Long?) : ViewModel() {
    private val _form = MutableStateFlow(AddSourceForm(editing = editId != null))
    val form = _form.asStateFlow()

    init {
        if (editId != null) viewModelScope.launch {
            val s = app.core.sources.byId(editId) ?: return@launch
            _form.update {
                it.copy(
                    type = s.type, name = s.name,
                    server = if (s.type == SourceType.XTREAM) s.url else "",
                    username = s.username.orEmpty(),
                    epgUrl = s.epgUrl.orEmpty(), userAgent = s.userAgent.orEmpty(),
                )
            }
        }
    }

    fun edit(block: (AddSourceForm) -> AddSourceForm) = _form.update { block(it).copy(error = null) }

    fun save(context: Context, onSaved: (Long, Boolean) -> Unit) {
        val f = _form.value
        if (f.loading) return
        val missing = when (f.type) {
            SourceType.XTREAM -> f.server.isBlank() || f.username.isBlank() || (f.password.isBlank() && editId == null)
            SourceType.M3U -> f.m3uUrl.isBlank()
        }
        if (missing) {
            _form.update { it.copy(error = context.getString(R.string.error_required)) }
            return
        }
        val input = when (f.type) {
            SourceType.XTREAM -> SourceInput.Xtream(f.name.trim(), f.server.trim(), f.username.trim(), f.password, f.userAgent.trim().ifEmpty { null })
            SourceType.M3U -> SourceInput.M3u(f.name.trim(), f.m3uUrl.trim(), f.epgUrl.trim().ifEmpty { null }, f.userAgent.trim().ifEmpty { null })
        }
        _form.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // Credentials are encrypted with the Keystore (disk + IPC): keep it off the main thread.
                if (editId == null) {
                    val id = withContext(Dispatchers.Default) { app.core.sources.add(input) }
                    onSaved(id, true)
                } else {
                    withContext(Dispatchers.Default) { app.core.sources.update(editId, input) }
                    app.appScope.launch { runCatching { app.core.sync.sync(editId) } }
                    onSaved(editId, false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _form.update { it.copy(loading = false, error = errorMessage(context, e)) }
            }
        }
    }
}

fun errorMessage(context: Context, e: Throwable): String = when (e) {
    is XtreamException -> context.getString(
        when (e.kind) {
            XtreamException.Kind.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
            XtreamException.Kind.ACCOUNT_EXPIRED -> R.string.error_account_expired
            XtreamException.Kind.NETWORK -> R.string.error_network
            XtreamException.Kind.SERVER -> R.string.error_server
            XtreamException.Kind.PARSE -> R.string.error_parse
        },
    )
    is java.io.IOException -> context.getString(R.string.error_network)
    else -> context.getString(R.string.error_generic, e.message ?: e::class.java.simpleName)
}

@Composable
fun AddSourceScreen(editId: Long?, onBack: (() -> Unit)?, onSaved: (Long, Boolean) -> Unit, preferPhone: Boolean = false) {
    val vm = appViewModel(key = "add_$editId") { AddSourceViewModel(it, editId) }
    val form by vm.form.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    // TV (and tablets): adding a list with the phone is the primary option.
    val isTv = com.steplayer.app.ui.theme.LocalIsTv.current
    val offerPhone = isTv || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    var usePhone by rememberSaveable { mutableStateOf(isTv || preferPhone) }

    Column(Modifier.fillMaxSize().background(Palette.Background).imePadding()) {
        AppTopBar(stringResource(if (form.editing) R.string.edit_list else R.string.add_list), onBack = onBack, large = true)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = if (offerPhone && usePhone) 900.dp else 620.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (offerPhone && !form.editing) {
                    SegmentedTabs(
                        options = listOf(stringResource(R.string.pair_use_phone), stringResource(R.string.pair_use_remote)),
                        selected = if (usePhone) 0 else 1,
                        onSelect = { usePhone = it == 0 },
                    )
                }
                if (offerPhone && usePhone && !form.editing) {
                    PairingPanel(onAdded = { id -> onSaved(id, true) }, modifier = Modifier.padding(vertical = 16.dp))
                    return@Column
                }
                if (!form.editing) {
                    SegmentedTabs(
                        options = listOf(stringResource(R.string.tab_xtream), stringResource(R.string.tab_m3u)),
                        selected = if (form.type == SourceType.XTREAM) 0 else 1,
                        onSelect = { i -> vm.edit { it.copy(type = if (i == 0) SourceType.XTREAM else SourceType.M3U) } },
                    )
                }
                Field(form.name, { v -> vm.edit { it.copy(name = v) } }, stringResource(R.string.field_name))
                if (form.type == SourceType.XTREAM) {
                    Text(stringResource(R.string.xtream_login_hint), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
                    Field(form.server, { v -> vm.edit { it.copy(server = v) } }, stringResource(R.string.field_server), KeyboardType.Uri)
                    Field(form.username, { v -> vm.edit { it.copy(username = v) } }, stringResource(R.string.field_username))
                    Field(
                        form.password, { v -> vm.edit { it.copy(password = v) } },
                        stringResource(if (form.editing) R.string.field_password_keep else R.string.field_password),
                        KeyboardType.Password,
                        visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    stringResource(if (showPassword) R.string.hide_password else R.string.show_password),
                                    tint = Palette.TextSecondary,
                                )
                            }
                        },
                    )
                } else {
                    Field(form.m3uUrl, { v -> vm.edit { it.copy(m3uUrl = v) } }, stringResource(R.string.field_m3u_url), KeyboardType.Uri)
                    Field(form.epgUrl, { v -> vm.edit { it.copy(epgUrl = v) } }, stringResource(R.string.field_epg_url), KeyboardType.Uri)
                }

                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { advanced = !advanced }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.advanced), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
                    Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Palette.TextSecondary)
                }
                AnimatedVisibility(advanced) {
                    Field(form.userAgent, { v -> vm.edit { it.copy(userAgent = v) } }, stringResource(R.string.field_user_agent))
                }

                form.error?.let {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Live.copy(alpha = 0.12f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Live)
                        Spacer(Modifier.size(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
                    }
                }

                Spacer(Modifier.height(4.dp))
                if (form.loading) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.connecting), color = Palette.TextSecondary)
                    }
                } else {
                    PrimaryButton(
                        stringResource(if (form.editing) R.string.save else R.string.save_and_connect),
                        onClick = { vm.save(context, onSaved) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    stringResource(R.string.disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Palette.Surface).padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .weight(1f)
                    .focusHighlight(shape, 1.02f)
                    .clip(shape)
                    .then(if (sel) Modifier.background(Palette.AccentGradient) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Palette.OnAccent else Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visual,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
            cursorColor = Palette.Accent,
            focusedLabelColor = Palette.Accent,
            unfocusedLabelColor = Palette.TextSecondary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- Sync

class SyncViewModel(private val app: StePlayerApp, private val sourceId: Long) : ViewModel() {
    sealed interface Result {
        data object Running : Result
        data object Done : Result
        data class Failed(val error: Throwable) : Result
    }

    private val _result = MutableStateFlow<Result>(Result.Running)
    val result = _result.asStateFlow()
    val progress = app.core.sync.state

    init { start() }

    fun start() {
        _result.value = Result.Running
        viewModelScope.launch {
            _result.value = try {
                // Reads the credentials from the Keystore and parses big lists: not on main.
                withContext(Dispatchers.Default) { app.core.sync.sync(sourceId) }
                Result.Done
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.Failed(e)
            }
        }
    }
}

@Composable
fun SyncScreen(sourceId: Long, onDone: () -> Unit) {
    val vm = appViewModel(key = "sync_$sourceId") { SyncViewModel(it, sourceId) }
    val result by vm.result.collectAsStateWithLifecycle()
    val state by vm.progress.collectAsStateWithLifecycle(SyncState.Idle)
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(result) { if (result is SyncViewModel.Result.Done) onDone() }

    Box(Modifier.fillMaxSize().background(Palette.Background).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val r = result) {
                is SyncViewModel.Result.Failed -> {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Live, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.sync_failed), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(errorMessage(context, r.error), color = Palette.TextSecondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton(stringResource(R.string.retry), onClick = vm::start, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(stringResource(R.string.continue_anyway), onClick = onDone, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Box(
                        Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(Palette.AccentGradient),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.CloudSync, null, tint = Palette.OnAccent, modifier = Modifier.size(40.dp)) }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    val running = state as? SyncState.Running
                    Text(
                        stringResource(
                            when (running?.step) {
                                SyncStep.LIVE -> R.string.sync_live
                                SyncStep.MOVIES -> R.string.sync_movies
                                SyncStep.SERIES -> R.string.sync_series
                                SyncStep.EPG -> R.string.sync_epg
                                SyncStep.SAVING -> R.string.sync_saving
                                else -> R.string.sync_auth
                            },
                        ),
                        color = Palette.TextSecondary,
                    )
                    Spacer(Modifier.height(24.dp))
                    ProgressBar(running?.progress ?: 0.02f, Modifier.fillMaxWidth(), height = 6.dp)
                }
            }
        }
    }
}
