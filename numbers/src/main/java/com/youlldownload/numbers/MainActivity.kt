package com.youlldownload.numbers

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                MainScreen(state, vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { vm.setFile(it) }
            }
            // "Open with Numbers Converter" from a file manager.
            Intent.ACTION_VIEW -> intent.data?.let { vm.setFile(it) }
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
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::setFile)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Numbers Converter") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Turn Apple Numbers spreadsheets (.numbers) into PDF and Word documents.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                // Any type: file managers rarely know the .numbers MIME type.
                onClick = { pickFile.launch(arrayOf("*/*")) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (state.fileUri == null) "Choose a .numbers file" else "Choose another file") }

            state.fileName?.let { n ->
                Text(
                    if (state.fileSize > 0) "$n · ${Formatter.formatFileSize(ctx, state.fileSize)}" else n,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!n.endsWith(".numbers", ignoreCase = true) && !n.endsWith(".zip", ignoreCase = true)) {
                    Text(
                        "This doesn't look like a .numbers file, but you can try.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column {
                Text("Convert to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CheckRow("PDF (.pdf)", state.pdf, !state.busy, vm::setPdf)
                CheckRow("Word (.docx)", state.word, !state.busy, vm::setWord)
            }

            Button(
                onClick = vm::convert,
                enabled = !state.busy && state.fileUri != null && (state.pdf || state.word),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Convert") }

            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.status.isNotEmpty()) Text(state.status, style = MaterialTheme.typography.bodyMedium)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.results.forEach { ResultCard(it) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ResultCard(file: SavedFile) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${file.label} saved ✓", style = MaterialTheme.typography.titleMedium)
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
