package dev.quinntyx.charon.organization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class OrganizationTab { FOLDERS, TAGS }

private sealed interface NameEditor {
    data class NewFolder(val initialName: String = "") : NameEditor
    data class RenameFolder(val folder: FolderAccount) : NameEditor
    data class NewTag(val initialName: String = "") : NameEditor
    data class RenameTag(val tag: TransactionTag) : NameEditor
}

private sealed interface DeleteTarget {
    data class Folder(val folder: FolderAccount) : DeleteTarget
    data class Tag(val tag: TransactionTag) : DeleteTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationScreen(
    repository: OrganizationRepository,
    modifier: Modifier = Modifier,
) {
    val snapshot by repository.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(OrganizationTab.FOLDERS) }
    var editor by remember { mutableStateOf<NameEditor?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var selectedFolderId by remember { mutableStateOf<FolderId?>(null) }
    var selectedTagId by remember { mutableStateOf<TagId?>(null) }

    LaunchedEffect(snapshot.activeFolders, selectedFolderId) {
        if (selectedFolderId !in snapshot.activeFolders.map { it.id }) selectedFolderId = null
    }
    LaunchedEffect(snapshot.activeTags, selectedTagId) {
        if (selectedTagId !in snapshot.activeTags.map { it.id }) selectedTagId = null
    }

    fun handle(result: OrganizationResult<*>, successMessage: String) {
        scope.launch {
            val message = when (result) {
                is OrganizationResult.Success -> successMessage
                is OrganizationResult.Failure -> result.reason.userMessage()
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Folders & tags") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == OrganizationTab.FOLDERS,
                    onClick = { tab = OrganizationTab.FOLDERS },
                    text = { Text("Folders") },
                )
                Tab(
                    selected = tab == OrganizationTab.TAGS,
                    onClick = { tab = OrganizationTab.TAGS },
                    text = { Text("Tags") },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    OrganizationTab.FOLDERS -> FolderList(
                        snapshot = snapshot,
                        onCreate = { editor = NameEditor.NewFolder() },
                        onRename = { editor = NameEditor.RenameFolder(it) },
                        onArchive = { folder, archived ->
                            scope.launch {
                                handle(
                                    repository.setFolderArchived(folder.id, archived),
                                    if (archived) "Folder archived" else "Folder restored",
                                )
                            }
                        },
                        onDelete = { deleteTarget = DeleteTarget.Folder(it) },
                    )

                    OrganizationTab.TAGS -> TagList(
                        snapshot = snapshot,
                        onCreate = { editor = NameEditor.NewTag() },
                        onRename = { editor = NameEditor.RenameTag(it) },
                        onArchive = { tag, archived ->
                            scope.launch {
                                handle(
                                    repository.setTagArchived(tag.id, archived),
                                    if (archived) "Tag archived" else "Tag restored",
                                )
                            }
                        },
                        onDelete = { deleteTarget = DeleteTarget.Tag(it) },
                    )
                }

                HorizontalDivider()
                Text("Entry selectors", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Archived items are intentionally unavailable when assigning a transaction.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FolderSelector(
                    folders = snapshot.activeFolders,
                    selectedId = selectedFolderId,
                    onSelected = { selectedFolderId = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                TagSelector(
                    tags = snapshot.activeTags,
                    selectedId = selectedTagId,
                    onSelected = { selectedTagId = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    editor?.let { currentEditor ->
        NameEditorDialog(
            editor = currentEditor,
            onDismiss = { editor = null },
            onSave = { enteredName ->
                scope.launch {
                    val result = when (currentEditor) {
                        is NameEditor.NewFolder -> repository.createFolder(enteredName)
                        is NameEditor.RenameFolder -> repository.renameFolder(
                            currentEditor.folder.id,
                            enteredName,
                        )
                        is NameEditor.NewTag -> repository.createTag(enteredName)
                        is NameEditor.RenameTag -> repository.renameTag(
                            currentEditor.tag.id,
                            enteredName,
                        )
                    }
                    if (result is OrganizationResult.Success) editor = null
                    handle(result, "Saved")
                }
            },
        )
    }

    deleteTarget?.let { target ->
        val label = when (target) {
            is DeleteTarget.Folder -> target.folder.name
            is DeleteTarget.Tag -> target.tag.name
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete $label?") },
            text = {
                Text(
                    "Only unused items can be deleted. Referenced items remain available to history " +
                        "and should be archived instead.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = when (target) {
                                is DeleteTarget.Folder -> repository.deleteFolder(target.folder.id)
                                is DeleteTarget.Tag -> repository.deleteTag(target.tag.id)
                            }
                            if (result is OrganizationResult.Success) deleteTarget = null
                            handle(result, "Deleted")
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FolderList(
    snapshot: OrganizationSnapshot,
    onCreate: () -> Unit,
    onRename: (FolderAccount) -> Unit,
    onArchive: (FolderAccount, Boolean) -> Unit,
    onDelete: (FolderAccount) -> Unit,
) {
    SectionHeader(
        title = "Accounts",
        actionLabel = "New folder",
        onAction = onCreate,
    )
    Text(
        "Folders are accounts: the source or destination of money. They are not transaction types.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (snapshot.activeFolders.isEmpty()) {
        EmptyState("No active folders", "Create an account such as Cash or Checking.")
    } else {
        snapshot.activeFolders.forEach { folder ->
            FolderRow(folder, onRename, onArchive, onDelete)
        }
    }
    if (snapshot.archivedFolders.isNotEmpty()) {
        Text("Archived", style = MaterialTheme.typography.titleMedium)
        snapshot.archivedFolders.forEach { folder ->
            FolderRow(folder, onRename, onArchive, onDelete)
        }
    }
}

@Composable
private fun TagList(
    snapshot: OrganizationSnapshot,
    onCreate: () -> Unit,
    onRename: (TransactionTag) -> Unit,
    onArchive: (TransactionTag, Boolean) -> Unit,
    onDelete: (TransactionTag) -> Unit,
) {
    SectionHeader(title = "Transaction types", actionLabel = "New tag", onAction = onCreate)
    Text(
        "Tags classify a transaction, such as Groceries. Merchant names are managed separately.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (snapshot.activeTags.isEmpty()) {
        EmptyState("No active tags", "Create a tag to classify expenses or income.")
    } else {
        snapshot.activeTags.forEach { tag -> TagRow(tag, onRename, onArchive, onDelete) }
    }
    if (snapshot.archivedTags.isNotEmpty()) {
        Text("Archived", style = MaterialTheme.typography.titleMedium)
        snapshot.archivedTags.forEach { tag -> TagRow(tag, onRename, onArchive, onDelete) }
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun FolderRow(
    folder: FolderAccount,
    onRename: (FolderAccount) -> Unit,
    onArchive: (FolderAccount, Boolean) -> Unit,
    onDelete: (FolderAccount) -> Unit,
) {
    OrganizationCard(
        title = folder.name,
        details = buildList {
            if (folder.balances.isEmpty()) add("No recorded balance")
            folder.balances.sortedBy { it.currencyCode }.forEach { add(it.displayText()) }
            if (folder.transactionCount > 0) add("${folder.transactionCount} transactions")
            if (folder.recurringRuleCount > 0) add("${folder.recurringRuleCount} recurring rules")
        },
        archived = folder.isArchived,
        onRename = { onRename(folder) },
        onArchive = { onArchive(folder, !folder.isArchived) },
        onDelete = { onDelete(folder) },
    )
}

@Composable
private fun TagRow(
    tag: TransactionTag,
    onRename: (TransactionTag) -> Unit,
    onArchive: (TransactionTag, Boolean) -> Unit,
    onDelete: (TransactionTag) -> Unit,
) {
    OrganizationCard(
        title = tag.name,
        details = buildList {
            if (tag.transactionCount == 0 && tag.recurringRuleCount == 0) add("Unused")
            if (tag.transactionCount > 0) add("${tag.transactionCount} transactions")
            if (tag.recurringRuleCount > 0) add("${tag.recurringRuleCount} recurring rules")
        },
        archived = tag.isArchived,
        onRename = { onRename(tag) },
        onArchive = { onArchive(tag, !tag.isArchived) },
        onDelete = { onDelete(tag) },
    )
}

@Composable
private fun OrganizationCard(
    title: String,
    details: List<String>,
    archived: Boolean,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (archived) Text("Archived", style = MaterialTheme.typography.labelMedium)
            }
            details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onArchive) { Text(if (archived) "Restore" else "Archive") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NameEditorDialog(
    editor: NameEditor,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val initialName = when (editor) {
        is NameEditor.NewFolder -> editor.initialName
        is NameEditor.RenameFolder -> editor.folder.name
        is NameEditor.NewTag -> editor.initialName
        is NameEditor.RenameTag -> editor.tag.name
    }
    val title = when (editor) {
        is NameEditor.NewFolder -> "New folder"
        is NameEditor.RenameFolder -> "Rename folder"
        is NameEditor.NewTag -> "New tag"
        is NameEditor.RenameTag -> "Rename tag"
    }
    var name by remember(editor) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FolderSelector(
    folders: List<FolderAccount>,
    selectedId: FolderId?,
    onSelected: (FolderId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorField(
        label = "Folder",
        emptyText = "No active folders available",
        choices = folders.filterNot { it.isArchived }.map { it.id to it.name },
        selectedKey = selectedId,
        onSelected = onSelected,
        modifier = modifier,
    )
}

@Composable
fun TagSelector(
    tags: List<TransactionTag>,
    selectedId: TagId?,
    onSelected: (TagId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorField(
        label = "Tag (optional)",
        emptyText = "No active tags available",
        choices = tags.filterNot { it.isArchived }.map { it.id to it.name },
        selectedKey = selectedId,
        onSelected = onSelected,
        modifier = modifier,
    )
}

@Composable
private fun <T> SelectorField(
    label: String,
    emptyText: String,
    choices: List<Pair<T, String>>,
    selectedKey: T?,
    onSelected: (T?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = choices.firstOrNull { it.first == selectedKey }?.second
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedName ?: if (choices.isEmpty()) emptyText else "Choose $label")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (selectedKey != null) {
                    DropdownMenuItem(
                        text = { Text("Clear selection") },
                        onClick = {
                            onSelected(null)
                            expanded = false
                        },
                    )
                }
                choices.forEach { (key, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelected(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun OrganizationFailure.userMessage(): String = when (this) {
    is OrganizationFailure.InvalidName -> message
    is OrganizationFailure.NameAlreadyExists -> "A folder or tag named $name already exists"
    OrganizationFailure.NotFound -> "This item no longer exists"
    is OrganizationFailure.DeletionBlocked -> buildString {
        append("Cannot delete: archive it to preserve")
        val reasons = buildList {
            if (transactionCount > 0) add("$transactionCount transactions")
            if (recurringRuleCount > 0) add("$recurringRuleCount recurring rules")
            if (nonZeroBalances.isNotEmpty()) {
                add(nonZeroBalances.joinToString { it.displayText() })
            }
        }
        if (reasons.isNotEmpty()) append(" ").append(reasons.joinToString())
    }
}
