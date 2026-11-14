package com.steplayer.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.steplayer.app.StePlayerApp
import com.steplayer.app.app

@Composable
fun rememberApp(): StePlayerApp = LocalContext.current.app

/** ViewModel factory with access to the app container (manual DI). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (StePlayerApp) -> VM): VM {
    val app = LocalContext.current.app
    return viewModel(key = key) { create(app) }
}

fun android.content.Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
