package com.ethora.chat.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.i18n.LanguageOption
import com.ethora.chat.core.i18n.canonicalLocale
import com.ethora.chat.core.i18n.languageChoices
import com.ethora.chat.core.networking.AccountException
import com.ethora.chat.core.networking.AccountService
import com.ethora.chat.core.service.LogoutService
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.ReaderLanguageStore
import com.ethora.chat.ui.i18n.ChatTranslator
import com.ethora.chat.ui.i18n.rememberChatTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outlives the composable, so a logout that finishes after the screen closed still runs its after-hook. */
private val logoutScope = MainScope()

/**
 * Account settings: language card, change password, manage data (export and delete account)
 * and logout. Port of web `UserSettingsModal` plus the React Native settings switches
 * (`settings.languages`, `settings.changePassword`, `logout`).
 *
 * The language card and password change show only when enabled in [ChatConfig.settings]. Manage
 * data is always shown. Logout is shown unless [ChatConfig.logout] sets `enabled = false`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = rememberChatTranslator()
    val config by ChatStore.config.collectAsState()
    val uiLanguage by ReaderLanguageStore.uiLanguage.collectAsState()
    val chatLanguage by ReaderLanguageStore.chatLanguage.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                    }.getOrDefault(false)
                }
                snackbar.showSnackbar(t(if (saved) "settings.export.saved" else "settings.export.failed"))
            }
        }
    }

    val languages = config?.settings?.languages
    val languagesEnabled = languages?.enabled == true
    val logoutConfig = config?.logout
    val logoutShown = logoutConfig?.enabled ?: true

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        t("settings.menu.title"),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = t("header.action.back"))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (languagesEnabled) {
                item {
                    SettingsCard(title = t("modal.profile.preferences"), hint = t("modal.profile.languageHint")) {
                        LanguageChoiceGroup(
                            title = t("settings.language.appTitle"),
                            options = languageChoices(languages?.appLanguages),
                            selectedTag = uiLanguage,
                            onSelect = { id -> scope.launch { AccountService.setAppLanguage(id) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        LanguageChoiceGroup(
                            title = t("settings.language.chatTitle"),
                            options = languageChoices(languages?.chatLanguages),
                            selectedTag = chatLanguage ?: uiLanguage,
                            onSelect = { id -> scope.launch { AccountService.setChatLanguage(id) } }
                        )
                    }
                }
            }

            if (config?.settings?.changePassword == true) {
                item {
                    SettingsCard(title = t("settings.password.title")) {
                        OutlinedButton(
                            onClick = { showPasswordDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(t("settings.password.title"))
                        }
                    }
                }
            }

            item {
                SettingsCard(
                    title = t("settings.manageData.title"),
                    hint = t("settings.manageData.rowHint")
                ) {
                    Text(
                        t("settings.manageData.section.export"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        t("settings.manageData.downloadDescription"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                exporting = true
                                val result = AccountService.exportData()
                                exporting = false
                                result.onSuccess { bytes ->
                                    pendingExport = bytes
                                    exportLauncher.launch("mydata.json")
                                }.onFailure {
                                    snackbar.showSnackbar(t("settings.export.failed"))
                                }
                            }
                        },
                        enabled = !exporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(t("settings.manageData.downloadLabel"))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        t("settings.manageData.deleteLabel"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        t("settings.manageData.deleteDescription"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        t("settings.manageData.deleteDisclosure"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(t("action.deleteMyAccount"))
                    }
                }
            }

            if (logoutShown) {
                item {
                    Button(
                        onClick = {
                            if (logoutConfig?.confirm != false) {
                                showLogoutDialog = true
                            } else {
                                performLogout(logoutConfig)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(logoutConfig?.label ?: t("action.logOut"))
                    }
                }
            }
        }
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            t = t,
            onDismiss = { showPasswordDialog = false },
            onSaved = {
                showPasswordDialog = false
                scope.launch { snackbar.showSnackbar(t("settings.password.saved")) }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteDialog = false },
            title = { Text(t("settings.manageData.deleteConfirmTitle")) },
            text = { Text(t("settings.manageData.deleteConfirmDescription")) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            val result = AccountService.deleteAccount()
                            deleting = false
                            result.onSuccess {
                                showDeleteDialog = false
                                LogoutService.performLogout(onComplete = { logoutConfig?.let { cfg -> logoutScope.launch { cfg.onAfterLogout?.invoke() } } })
                            }.onFailure {
                                showDeleteDialog = false
                                snackbar.showSnackbar(t("settings.manageData.deleteFailed"))
                            }
                        }
                    }
                ) {
                    Text(
                        if (deleting) t("settings.manageData.deleting") else t("action.deleteMyAccount"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { showDeleteDialog = false }) {
                    Text(t("action.cancel"))
                }
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(logoutConfig?.confirmTitle ?: t("settings.logout.confirmTitle")) },
            text = { Text(logoutConfig?.confirmMessage ?: t("settings.logout.confirmMessage")) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    performLogout(logoutConfig)
                }) {
                    Text(logoutConfig?.confirmButton ?: t("action.logOut"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(logoutConfig?.cancelButton ?: t("action.cancel"))
                }
            }
        )
    }
}

/** Runs the host's before-hook, then the SDK logout, then the after-hook once the session is gone. */
private fun performLogout(config: com.ethora.chat.core.config.LogoutConfig?) {
    logoutScope.launch {
        val proceed = config?.onBeforeLogout?.invoke() ?: true
        if (!proceed) return@launch
        LogoutService.performLogout(onComplete = {
            logoutScope.launch { config?.onAfterLogout?.invoke() }
        })
    }
}

@Composable
private fun SettingsCard(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Column(modifier = Modifier.padding(top = 12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun LanguageChoiceGroup(
    title: String,
    options: List<LanguageOption>,
    selectedTag: String?,
    onSelect: (String) -> Unit
) {
    val selected = selectedTag?.let { canonicalLocale(it) }
    Text(title, style = MaterialTheme.typography.titleSmall)
    options.forEach { option ->
        val isSelected = selected != null && option.id.equals(selected, ignoreCase = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(
                    selected = isSelected,
                    onClick = { onSelect(option.id) },
                    role = androidx.compose.ui.semantics.Role.RadioButton
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(option.name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    t: ChatTranslator,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(t("settings.password.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = { Text(t("settings.password.current")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = { Text(t("settings.password.new")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(t("settings.password.confirm")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && current.isNotBlank() && next.isNotBlank(),
                onClick = {
                    if (next != confirm) {
                        error = t("settings.password.mismatch")
                        return@TextButton
                    }
                    scope.launch {
                        saving = true
                        error = null
                        val result = AccountService.changePassword(current, next)
                        saving = false
                        result.onSuccess { onSaved() }.onFailure { failure ->
                            error = (failure as? AccountException)?.serverMessage ?: t("settings.password.failed")
                        }
                    }
                }
            ) {
                Text(t("action.save"))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(t("action.cancel"))
            }
        }
    )
}
