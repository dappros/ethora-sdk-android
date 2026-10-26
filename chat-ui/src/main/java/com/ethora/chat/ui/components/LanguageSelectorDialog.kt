package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ethora.chat.core.config.TranslateMode
import com.ethora.chat.core.i18n.LANGUAGE_OPTIONS
import com.ethora.chat.core.i18n.canonicalLocale
import com.ethora.chat.core.i18n.canReaderChooseTranslateMode
import com.ethora.chat.core.i18n.resolveTranslateMode
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.ReaderLanguageStore
import com.ethora.chat.ui.i18n.rememberChatTranslator

/**
 * Globe button in the chat header that opens [LanguageSelectorDialog]. Shown when translation
 * is on and the host did not hide the selector (web `shouldShowLanguageSelector`).
 */
@Composable
fun LanguageSelectorButton(modifier: Modifier = Modifier) {
    val t = rememberChatTranslator()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = t("language.select")
        )
    }
    if (open) {
        LanguageSelectorDialog(onDismiss = { open = false })
    }
}

/**
 * Reader's language picker (web `LanguageSelectorModal`): the "Translate my messages" switch,
 * the auto/manual switch (unless the host pins the mode), and the language list. Picks apply
 * at once through [ReaderLanguageStore].
 */
@Composable
fun LanguageSelectorDialog(onDismiss: () -> Unit) {
    val t = rememberChatTranslator()
    val config by ChatStore.config.collectAsState()
    val chatLanguage by ReaderLanguageStore.chatLanguage.collectAsState()
    val sendEnabledPick by ReaderLanguageStore.translateSendEnabled.collectAsState()
    val readerMode by ReaderLanguageStore.translateMode.collectAsState()

    val translates = config?.translates
    val translatesEnabled = translates?.enabled == true
    // Undefined (never touched) reads as on, matching the send path.
    val sendEnabled = sendEnabledPick != false
    val canChooseMode = canReaderChooseTranslateMode(translates)
    val effectiveMode = resolveTranslateMode(translates, readerMode)
    val showLanguageList = translates?.showLanguageList != false
    val selected = chatLanguage?.let { canonicalLocale(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("language.select")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (translatesEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(t("translation.enableToggleLabel"), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = sendEnabled,
                            onCheckedChange = { ReaderLanguageStore.setTranslateSendEnabled(it) }
                        )
                    }
                    Text(
                        t("translation.enableToggleDisclaimer"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (canChooseMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = effectiveMode == TranslateMode.AUTO,
                                onClick = { ReaderLanguageStore.setTranslateMode(TranslateMode.AUTO) },
                                label = { Text(t("translation.modeAuto")) }
                            )
                            FilterChip(
                                selected = effectiveMode == TranslateMode.MANUAL,
                                onClick = { ReaderLanguageStore.setTranslateMode(TranslateMode.MANUAL) },
                                label = { Text(t("translation.modeManual")) }
                            )
                        }
                    }
                    if (showLanguageList) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                if (showLanguageList) {
                    LANGUAGE_OPTIONS.forEach { option ->
                        val isSelected = selected != null && option.id.equals(selected, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { ReaderLanguageStore.setChatLanguage(option.id) }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(option.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("action.close")) }
        }
    )
}
