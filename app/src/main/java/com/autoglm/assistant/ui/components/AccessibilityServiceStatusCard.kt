package com.autoglm.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoglm.assistant.accessibility.UIHierarchyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * # 无障碍服务状态卡片
 * 
 * ## 业务目的
 * 显示 Provider 应用的连接状态和无障碍服务启用状态
 * 
 * ## 实现逻辑
 * - 步骤1: 定时检查服务连接状态  
 * - 步骤2: 显示绿色/红色状态指示器
 * - 步骤3: 点击可跳转到系统设置
 */
@Composable
fun AccessibilityServiceStatusCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(false) }
    val isConnected by UIHierarchyManager.isBound.collectAsState()
    val scope = rememberCoroutineScope()
    
    // 步骤1: 定时检查无障碍服务启用状态
    LaunchedEffect(Unit) {
        while (true) {
            scope.launch {
                isEnabled = UIHierarchyManager.isAccessibilityServiceEnabled(context)
            }
            delay(2000) // 每2秒检查一次
        }
    }
    
    // 步骤2: 显示状态卡片
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenSettings() },
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled && isConnected) {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            } else {
                Color(0xFFFF9800).copy(alpha = 0.1f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：状态图标和文字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isEnabled && isConnected) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = if (isEnabled && isConnected) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFFF9800)
                    },
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = "无障碍服务",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = when {
                            isEnabled && isConnected -> "✅ 已启用并连接"
                            isEnabled && !isConnected -> "⚠️ 已启用但未连接"
                            else -> "❌ 未启用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 右侧：设置按钮
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "打开设置"
                )
            }
        }
    }
}
