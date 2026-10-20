package moe.antimony.hoshi.features.settings

import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.asString

/** Only confirmed persisted settings reach the controls inside [content]. */
@Composable
fun <T> SettingsDetailScaffold(
    state: SettingsLoadState<T>,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = hoshiSurfaces.page,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    actions: @Composable RowScope.() -> Unit = {},
    onRetry: (() -> Unit)? = null,
    content: @Composable (PaddingValues, T) -> Unit,
) {
    StaticSettingsDetailScaffold(title, onClose, modifier, containerColor, contentColor, actions) { padding ->
        SettingsContent(state, Modifier.fillMaxSize().padding(padding), onRetry) { settings ->
            content(padding, settings)
        }
    }
}

/** Also used by settings sheets and embedded settings panes. */
@Composable
internal fun <T> SettingsContent(
    state: SettingsLoadState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is SettingsLoadState.Loaded -> content(state.value)
        SettingsLoadState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.loading))
        }
        is SettingsLoadState.Failed -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorDetailsText(state.message.asString(), Modifier.weight(1f, fill = false))
                if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.settings_retry)) }
            }
        }
    }
}

/** Static menus and operations only; persisted controls use [SettingsDetailScaffold]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaticSettingsDetailScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = hoshiSurfaces.page,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = containerColor,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    scrolledContainerColor = containerColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = contentColor,
                    actionIconContentColor = contentColor,
                ),
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
            )
        },
        content = content,
    )
}
