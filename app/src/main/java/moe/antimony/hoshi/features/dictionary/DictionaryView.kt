package moe.antimony.hoshi.features.dictionary

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DictionaryRepository(context.filesDir, context.cacheDir) }
    var dictionaries by remember { mutableStateOf<List<DictionaryInfo>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        dictionaries = repository.loadDictionaries(DictionaryType.Term)
        runCatching { repository.rebuildLookupQuery() }
    }

    fun importDictionary(uri: Uri) {
        scope.launch {
            isImporting = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.importDictionary(context.contentResolver, uri, DictionaryType.Term)
                }
            }.onSuccess {
                reload()
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to import dictionary."
            }
            isImporting = false
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        importDictionary(uri)
    }

    LaunchedEffect(Unit) {
        reload()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Dictionaries") },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Text("‹")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !isImporting,
                    ) {
                        Text("+")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            isImporting -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            dictionaries.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(errorMessage ?: "No Dictionaries")
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                errorMessage?.let { Text(it) }
                dictionaries.forEach { dictionary ->
                    ListItem(
                        headlineContent = { Text(dictionary.index.title) },
                        supportingContent = { Text(dictionary.index.revision) },
                    )
                }
            }
        }
    }
}
