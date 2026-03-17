package com.ethora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethora.chat.core.store.LogStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsView(modifier: Modifier = Modifier) {
    val logs by LogStore.logs.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val expandedItems = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Application Logs") },
            actions = {
                IconButton(onClick = {
                    val allLogs = LogStore.getAllLogsAsText()
                    clipboardManager.setText(AnnotatedString(allLogs))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all logs")
                }
                IconButton(onClick = { LogStore.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(logs) { entry ->
                val itemKey = "${entry.timestamp}-${entry.tag}-${entry.type}-${entry.message.hashCode()}"
                val isExpanded = expandedItems[itemKey] == true
                LogEntryItem(
                    entry = entry,
                    expanded = isExpanded,
                    onToggleExpanded = { expandedItems[itemKey] = !isExpanded }
                )
                Divider(color = Color.LightGray.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
fun LogEntryItem(
    entry: LogStore.LogEntry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val color = when (entry.type) {
        LogStore.LogType.ERROR -> Color(0xFFD32F2F)
        LogStore.LogType.WARNING -> Color(0xFFFFA000)
        LogStore.LogType.SUCCESS -> Color(0xFF388E3C)
        LogStore.LogType.SEND -> Color(0xFF1976D2)
        LogStore.LogType.RECEIVE -> Color(0xFF7B1FA2)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onToggleExpanded()
            }
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = color
                )
            }
            
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = color.copy(alpha = 0.1f)
            ) {
                Text(
                    text = entry.type.name,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontSize = 8.sp
                )
            }
        }
        
        val displayText = if (expanded) {
            entry.message
        } else {
            entry.message.replace("\n", " ").take(220) + if (entry.message.length > 220) "..." else ""
        }

        Text(
            text = displayText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onToggleExpanded) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (expanded) "Collapse" else "Expand")
            }
            TextButton(
                onClick = {
                    clipboardManager.setText(
                        AnnotatedString("[${entry.timestamp}] [${entry.tag}] ${entry.message}")
                    )
                }
            ) {
                Text("Copy")
            }
        }
    }
}
