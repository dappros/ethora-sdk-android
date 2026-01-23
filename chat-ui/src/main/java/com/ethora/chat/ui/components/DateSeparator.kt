package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

/**
 * Date separator component - shows date labels between messages
 * Similar to web DateLabel component
 */
@Composable
fun DateSeparator(
    date: Date,
    modifier: Modifier = Modifier
) {
    val today = Calendar.getInstance().apply {
        time = Date()
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    
    val yesterday = Calendar.getInstance().apply {
        time = today.time
        add(Calendar.DAY_OF_YEAR, -1)
    }
    
    val messageDate = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    
    val label = when {
        isSameDay(messageDate, today) -> "Today"
        isSameDay(messageDate, yesterday) -> "Yesterday"
        messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> {
            // Same year - show month and day
            SimpleDateFormat("MMMM d", Locale.getDefault()).format(date)
        }
        else -> {
            // Different year - show month, day, and year
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
        }
    }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left line
        Divider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Date label
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Right line
        Divider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    }
}

/**
 * Check if two Calendar instances represent the same day
 */
private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}
