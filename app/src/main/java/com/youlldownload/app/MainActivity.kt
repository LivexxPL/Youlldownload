package com.youlldownload.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                // Keep the screen on while working so long compressions aren't interrupted.
                LaunchedEffect(state.busy) {
                    if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                MainScreen(state, vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.onSharedText(it) }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Youlldownload") },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Update yt-dlp") },
                                enabled = !state.busy && state.engineReady,
                                onClick = { menuOpen = false; vm.updateEngine() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::setUrl,
                label = { Text("Paste a video link") },
                placeholder = { Text("https://…") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.start() }),
                trailingIcon = {
                    if (state.url.isEmpty()) {
                        TextButton(onClick = { pasteClipboard(ctx)?.let(vm::onSharedText) }, enabled = !state.busy) {
                            Text("Paste")
                        }
                    } else {
                        TextButton(onClick = { vm.setUrl("") }, enabled = !state.busy) { Text("Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Picker(
                    label = "Format",
                    value = state.format.label,
                    options = OutFormat.entries.map { it.label },
                    enabled = !state.busy,
                    onPick = { vm.setFormat(OutFormat.entries[it]) },
                    modifier = Modifier.weight(1f),
                )
                Picker(
                    label = if (state.compress) "Max resolution" else "Resolution",
                    value = if (state.format.isAudio) "—" else state.resolution.label,
                    options = Resolution.entries.map { it.label },
                    enabled = !state.busy && !state.format.isAudio,
                    onPick = { vm.setResolution(Resolution.entries[it]) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compress to under 10 MB", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Uses the full 10 MB for the best possible quality. Takes longer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.compress, onCheckedChange = vm::setCompress, enabled = !state.busy)
            }

            if (state.busy) {
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Cancel")
                }
            } else {
                Button(
                    onClick = vm::start,
                    enabled = state.engineReady && state.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Download") }
            }

            if (state.busy || !state.engineReady) {
                val p = state.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.status.isNotEmpty()) {
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.result?.let { ResultCard(it) }
        }
    }
}

@Composable
private fun Picker(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(" ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onPick(i) })
                }
            }
        }
    }
}

@Composable
private fun ResultCard(file: SavedFile) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved ✓", style = MaterialTheme.typography.titleMedium)
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formatter.formatFileSize(ctx, file.sizeBytes)} · Downloads/$SAVE_FOLDER",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { ctx.startActivity(i) }
                        .onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
                }) { Text("Open") }
                OutlinedButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType(file.mime)
                        .putExtra(Intent.EXTRA_STREAM, file.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(i, "Share"))
                }) { Text("Share") }
            }
        }
    }
}

private fun pasteClipboard(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    if (text.isNullOrBlank()) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
    return text?.takeIf { it.isNotBlank() }
}
