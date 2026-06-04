package moe.antimony.hoshi.features.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.dictionary.DictionaryLanguage
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SectionTitle
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings

@Composable
fun LearningProfilesView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val profilesRepository = appContainer.learningProfilesRepository
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val dictionaryRepository = appContainer.dictionaryRepository
    val profilesState = profilesRepository.state.collectAsLoadedSettings()
    val dictionarySettings = dictionarySettingsRepository.settings.collectAsLoadedSettings()
    val scope = rememberCoroutineScope()
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<LearningProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<LearningProfile?>(null) }

    LaunchedEffect(
        profilesState?.activeProfile?.lookupLanguage,
        dictionarySettings?.lookupLanguage,
    ) {
        val loadedProfilesState = profilesState ?: return@LaunchedEffect
        val loadedDictionarySettings = dictionarySettings ?: return@LaunchedEffect
        if (
            loadedProfilesState.hasSingleDefaultProfile &&
            loadedProfilesState.activeProfile.lookupLanguage != loadedDictionarySettings.lookupLanguage
        ) {
            profilesRepository.updateActiveLookupLanguage(loadedDictionarySettings.lookupLanguage)
        }
    }

    LaunchedEffect(profilesState?.activeProfile?.id, profilesState?.activeProfile?.dictionarySelections) {
        val loadedProfilesState = profilesState ?: return@LaunchedEffect
        if (loadedProfilesState.activeProfile.dictionarySelections != null) return@LaunchedEffect
        val currentSelections = withContext(Dispatchers.IO) {
            dictionaryRepository.currentConfig().toProfileDictionarySelections()
        }
        profilesRepository.updateActiveDictionarySelections(currentSelections)
    }

    fun selectProfile(profile: LearningProfile) {
        scope.launch {
            val currentSelections = withContext(Dispatchers.IO) {
                dictionaryRepository.currentConfig().toProfileDictionarySelections()
            }
            profilesRepository.selectProfile(profile.id, currentSelections)?.let { selectedProfile ->
                dictionarySettingsRepository.update { settings ->
                    settings.copy(lookupLanguage = selectedProfile.lookupLanguage)
                }
                selectedProfile.dictionarySelections?.let { selections ->
                    withContext(Dispatchers.IO) {
                        dictionaryRepository.saveConfig(
                            dictionaryRepository.currentConfig().withProfileDictionarySelections(selections),
                        )
                    }
                }
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    SettingsDetailScaffold(
        title = stringResource(R.string.profiles_title),
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentColor = colorScheme.onBackground,
        actions = {
            IconButton(onClick = { showAddProfileDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.profiles_add_profile),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SectionTitle(stringResource(R.string.profiles_title))
                GroupCard {
                    val loadedProfilesState = profilesState
                    if (loadedProfilesState == null) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.loading)) },
                        )
                    } else {
                        loadedProfilesState.profiles.forEachIndexed { index, profile ->
                            LearningProfileRow(
                                profile = profile,
                                isActive = profile.id == loadedProfilesState.activeProfileId,
                                canDelete = loadedProfilesState.profiles.size > 1,
                                onSelect = { selectProfile(profile) },
                                onEdit = { editingProfile = profile },
                                onDelete = { deletingProfile = profile },
                            )
                            if (index != loadedProfilesState.profiles.lastIndex) {
                                GroupDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddProfileDialog) {
        ProfileEditorDialog(
            title = stringResource(R.string.profiles_add_profile),
            suggestedName = stringResource(
                R.string.profiles_new_name_format,
                (profilesState?.profiles?.size ?: 0) + 1,
            ),
            initialLanguage = dictionarySettings?.lookupLanguage
                ?: profilesState?.activeProfile?.lookupLanguage
                ?: DictionaryLanguage.Default,
            onDismiss = { showAddProfileDialog = false },
            onSave = { name, language ->
                showAddProfileDialog = false
                scope.launch {
                    val currentSelections = withContext(Dispatchers.IO) {
                        dictionaryRepository.currentConfig().toProfileDictionarySelections()
                    }
                    profilesRepository.addProfile(name, language, currentSelections)
                    dictionarySettingsRepository.update { settings ->
                        settings.copy(lookupLanguage = language)
                    }
                }
            },
        )
    }

    editingProfile?.let { profile ->
        ProfileEditorDialog(
            title = stringResource(R.string.profiles_edit_profile),
            profile = profile,
            suggestedName = profile.name,
            initialLanguage = profile.lookupLanguage,
            onDismiss = { editingProfile = null },
            onSave = { name, language ->
                editingProfile = null
                scope.launch {
                    val updatedProfile = profilesRepository.updateProfile(profile.id, name, language)
                    if (profilesState?.activeProfileId == profile.id && updatedProfile != null) {
                        dictionarySettingsRepository.update { settings ->
                            settings.copy(lookupLanguage = updatedProfile.lookupLanguage)
                        }
                    }
                }
            },
        )
    }

    deletingProfile?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            onDismiss = { deletingProfile = null },
            onConfirm = {
                deletingProfile = null
                scope.launch {
                    val activeProfile = profilesRepository.deleteProfile(profile.id)
                    if (profilesState?.activeProfileId == profile.id) {
                        dictionarySettingsRepository.update { settings ->
                            settings.copy(lookupLanguage = activeProfile.lookupLanguage)
                        }
                        activeProfile.dictionarySelections?.let { selections ->
                            withContext(Dispatchers.IO) {
                                dictionaryRepository.saveConfig(
                                    dictionaryRepository.currentConfig().withProfileDictionarySelections(selections),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LearningProfileRow(
    profile: LearningProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            RadioButton(
                selected = isActive,
                onClick = onSelect,
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    Text(
                        text = stringResource(R.string.profiles_active),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(stringResource(profile.lookupLanguage.labelRes))
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                    )
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ProfileEditorDialog(
    title: String,
    suggestedName: String,
    initialLanguage: DictionaryLanguage,
    onDismiss: () -> Unit,
    onSave: (String, DictionaryLanguage) -> Unit,
    profile: LearningProfile? = null,
) {
    var name by remember(profile?.id, suggestedName) { mutableStateOf(profile?.name ?: suggestedName) }
    var language by remember(profile?.id, initialLanguage) { mutableStateOf(initialLanguage) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box {
                    ListItem(
                        modifier = Modifier.clickable { languageMenuExpanded = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.profiles_language)) },
                        supportingContent = { Text(stringResource(language.labelRes)) },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false },
                    ) {
                        DictionaryLanguage.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes)) },
                                onClick = {
                                    language = option
                                    languageMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmedName, language) },
                enabled = trimmedName.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeleteProfileDialog(
    profile: LearningProfile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_delete_title_format, profile.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
