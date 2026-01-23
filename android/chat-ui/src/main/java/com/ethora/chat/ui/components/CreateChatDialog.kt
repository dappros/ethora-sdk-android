package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethora.chat.core.models.RoomType

/**
 * Dialog for creating a new chat
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatDialog(
    onDismiss: () -> Unit,
    onCreateChat: (String, RoomType, String?, List<String>?) -> Unit
) {
    var title by remember { mutableStateOf(TextFieldValue("")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }
    var selectedType by remember { mutableStateOf(RoomType.PRIVATE) }
    var showTypeSelector by remember { mutableStateOf(false) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Create New Chat",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                // Chat title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Chat Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Enter chat name") }
                )
                
                // Chat type selector
                ExposedDropdownMenuBox(
                    expanded = showTypeSelector,
                    onExpandedChange = { showTypeSelector = !showTypeSelector }
                ) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Chat Type") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showTypeSelector) }
                    )
                    ExposedDropdownMenu(
                        expanded = showTypeSelector,
                        onDismissRequest = { showTypeSelector = false }
                    ) {
                        RoomType.values().forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    selectedType = type
                                    showTypeSelector = false
                                }
                            )
                        }
                    }
                }
                
                // Description (optional)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = { Text("Enter description") }
                )
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.text.isNotBlank()) {
                                onCreateChat(
                                    title.text.trim(),
                                    selectedType,
                                    description.text.takeIf { it.isNotBlank() },
                                    null // Members can be added later
                                )
                            }
                        },
                        enabled = title.text.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
