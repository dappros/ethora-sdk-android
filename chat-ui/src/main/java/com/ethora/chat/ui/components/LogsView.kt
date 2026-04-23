package com.ethora.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val expandedItems = remember { mutableStateMapOf<Long, Boolean>() }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("all") }

    val categories = remember(logs) {
        listOf("all") + logs.map { it.category }.distinct().sorted()
    }
    val filteredLogs = remember(logs, query, categoryFilter) {
        logs.filter { entry ->
            val matchesQuery = query.isBlank() || buildString {
                append(entry.tag)
                append(' ')
                append(entry.category)
                append(' ')
                append(entry.message)
                append(' ')
                append(entry.rawMessage ?: "")
            }.contains(query, ignoreCase = true)
            val matchesCategory = categoryFilter == "all" || entry.category == categoryFilter
            matchesQuery && matchesCategory
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Application Logs") },
            actions = {
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(LogStore.copyAllLogs()))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all logs")
                }
                IconButton(onClick = { LogStore.clear(startNewSession = true) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                }
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter logs") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.take(8).forEach { category ->
                        FilterChip(
                            selected = categoryFilter == category,
                            onClick = { categoryFilter = category },
                            label = { Text(category) }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(filteredLogs, key = { it.eventId }) { entry ->
                val isExpanded = expandedItems[entry.eventId] == true
                LogEntryItem(
                    entry = entry,
                    expanded = isExpanded,
                    onToggleExpanded = { expandedItems[entry.eventId] = !isExpanded }
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
    val fullText = buildString {
        append("[${entry.timestamp}] [t+${entry.relativeMs}ms] [${entry.type}] [${entry.tag}] [${entry.category}] ")
        append(entry.message)
        entry.rawMessage?.takeIf { it.isNotBlank() }?.let {
            append("\nraw=")
            append(it)
        }
    }
    val collapsedText = fullText.replace("\n", " ").take(260) + if (fullText.length > 260) "..." else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "t+${entry.relativeMs}ms • ${entry.tag} • ${entry.category}",
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

        Text(
            text = if (expanded) fullText else collapsedText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
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
                onClick = { clipboardManager.setText(AnnotatedString(fullText)) }
            ) {
                Text("Copy")
            }
        }
    }
}
