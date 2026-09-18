package moe.antimony.hoshi.features.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import moe.antimony.hoshi.ui.HoshiButton as Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SectionTitle
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.profiles.HoshiProfile
import moe.antimony.hoshi.profiles.ProfileState

@Composable
internal fun ProfilesView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.profileState.collectAsStateWithLifecycle()
    ProfilesContent(
        state = state,
        onClose = onClose,
        onCreateProfile = viewModel::createProfile,
        onRenameProfile = viewModel::renameProfile,
        onDeleteProfile = viewModel::deleteProfile,
        onActivateGlobal = viewModel::activateGlobal,
        onSetPrimary = viewModel::setPrimaryProfile,
        modifier = modifier,
    )
}

@Composable
private fun ProfilesContent(
    state: ProfileState,
    onClose: () -> Unit,
    onCreateProfile: (String, String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onActivateGlobal: (String) -> Unit,
    onSetPrimary: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingProfile by remember { mutableStateOf<HoshiProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<HoshiProfile?>(null) }
    var creatingProfile by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        title = stringResource(R.string.profiles_title),
        onClose = onClose,
        modifier = modifier,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                ActiveProfileSection(
                    state = state,
                    onActivateGlobal = onActivateGlobal,
                )
            }
            items(state.profileLanguageGroups(), key = { it.language.dictionaryLanguageId }) { group ->
                LanguageProfileSection(
                    group = group,
                    onEdit = { profile -> editingProfile = profile },
                    onDelete = { profile -> deletingProfile = profile },
                    onSetPrimary = { profile ->
                        onSetPrimary(group.language.dictionaryLanguageId, profile.id)
                    },
                )
            }
            item {
                OutlinedButton(
                    onClick = { creatingProfile = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.profiles_create))
                }
            }
        }
    }

    if (creatingProfile) {
        ProfileEditDialog(
            title = stringResource(R.string.profiles_create),
            initialName = "",
            initialLanguageId = state.createProfileInitialLanguageId(),
            allowLanguageChange = true,
            supportingText = stringResource(R.string.profiles_create_copy_settings_note),
            onDismiss = { creatingProfile = false },
            onConfirm = { name, languageId ->
                onCreateProfile(name, languageId)
                creatingProfile = false
            },
        )
    }

    editingProfile?.let { profile ->
        ProfileEditDialog(
            title = profile.name,
            initialName = profile.name,
            initialLanguageId = profile.dictionaryLanguageId,
            allowLanguageChange = false,
            onDismiss = { editingProfile = null },
            onConfirm = { name, _ ->
                onRenameProfile(profile.id, name)
                editingProfile = null
            },
        )
    }

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.profiles_delete_title_format, profile.name)) },
            text = {
                Text(
                    if (profile.isDefault) {
                        stringResource(R.string.profiles_default_cannot_delete)
                    } else {
                        profile.name
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !profile.isDefault,
                    onClick = {
                        onDeleteProfile(profile.id)
                        deletingProfile = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deletingProfile = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ActiveProfileSection(
    state: ProfileState,
    onActivateGlobal: (String) -> Unit,
) {
    Column {
        SectionTitle(stringResource(R.string.profiles_active_global))
        GroupCard {
            if (state.profiles.size > 1) {
                Column(modifier = Modifier.selectableGroup()) {
                    state.profiles.forEachIndexed { index, profile ->
                        ProfileSelectionRow(
                            profile = profile,
                            supportingText = profile.languageDisplayName(),
                            selected = state.globalActiveProfileId == profile.id,
                            onSelected = { onActivateGlobal(profile.id) },
                        )
                        if (index != state.profiles.lastIndex) {
                            GroupDivider()
                        }
                    }
                }
            } else {
                ProfileReadOnlyRow(
                    profile = state.globalActiveProfile,
                    supportingText = stringResource(R.string.profiles_current_profile),
                    onEdit = null,
                    onDelete = null,
                )
            }
            state.loadedProfileId?.let {
                GroupDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.profiles_loaded_reader)) },
                    supportingContent = { Text(state.effectiveProfile.name) },
                )
            }
        }
    }
}

@Composable
private fun LanguageProfileSection(
    group: ProfileLanguageGroup,
    onEdit: (HoshiProfile) -> Unit,
    onDelete: (HoshiProfile) -> Unit,
    onSetPrimary: (HoshiProfile) -> Unit,
) {
    val languageName = stringResource(group.language.displayNameRes)
    Column {
        SectionTitle(stringResource(R.string.profiles_default_for_language_format, languageName))
        GroupCard {
            if (group.canChooseDefault) {
                Column(modifier = Modifier.selectableGroup()) {
                    group.profiles.forEachIndexed { index, profile ->
                        ProfileSelectionRow(
                            profile = profile,
                            supportingText = null,
                            selected = group.defaultProfileId == profile.id,
                            onSelected = { onSetPrimary(profile) },
                            onEdit = { onEdit(profile) },
                            onDelete = { onDelete(profile) },
                        )
                        if (index != group.profiles.lastIndex) {
                            GroupDivider()
                        }
                    }
                }
            } else {
                val profile = group.profiles.first()
                ProfileReadOnlyRow(
                    profile = profile,
                    supportingText = null,
                    onEdit = { onEdit(profile) },
                    onDelete = { onDelete(profile) },
                )
            }
        }
    }
}

@Composable
private fun ProfileSelectionRow(
    profile: HoshiProfile,
    supportingText: String?,
    selected: Boolean,
    onSelected: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(profile.name) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        trailingContent = profileActions(onEdit, onDelete),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelected,
                role = Role.RadioButton,
            ),
    )
}

@Composable
private fun ProfileReadOnlyRow(
    profile: HoshiProfile,
    supportingText: String?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(profile.name) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = profileActions(onEdit, onDelete),
    )
}

@Composable
private fun profileActions(
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
): (@Composable () -> Unit)? =
    if (onEdit == null && onDelete == null) {
        null
    } else {
        {
            Row {
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_rename))
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
        }
    }

@Composable
private fun HoshiProfile.languageDisplayName(): String {
    val language = ContentLanguageProfile.fromDictionaryLanguageId(dictionaryLanguageId)
        ?: ContentLanguageProfile.Default
    return stringResource(language.displayNameRes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditDialog(
    title: String,
    initialName: String,
    initialLanguageId: String,
    allowLanguageChange: Boolean,
    supportingText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var languageId by remember(initialLanguageId) { mutableStateOf(initialLanguageId) }
    var languageExpanded by remember { mutableStateOf(false) }
    val selectedLanguage = ContentLanguageProfile.fromDictionaryLanguageId(languageId) ?: ContentLanguageProfile.Default

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                supportingText?.let { Text(it) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_new_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { if (allowLanguageChange) languageExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(selectedLanguage.displayNameRes),
                        onValueChange = {},
                        readOnly = true,
                        enabled = allowLanguageChange,
                        label = { Text(stringResource(R.string.profiles_language)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = allowLanguageChange)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        containerColor = moe.antimony.hoshi.ui.theme.hoshiSurfaces.overlay,
                        border = moe.antimony.hoshi.ui.theme.hoshiContainerBorder(),
                        tonalElevation = 0.dp,
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        ContentLanguageProfile.Supported.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(stringResource(language.displayNameRes)) },
                                onClick = {
                                    languageId = language.dictionaryLanguageId
                                    languageExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, languageId) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
