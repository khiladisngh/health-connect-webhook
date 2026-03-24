package com.hcwebhook.app.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import com.hcwebhook.app.HealthConnectManager
import com.hcwebhook.app.HealthDataType

private val shortDisplayNames = mapOf(
    HealthDataType.HEART_RATE_VARIABILITY to "HRV (RMSSD)",
    HealthDataType.BASAL_METABOLIC_RATE to "BMR",
    HealthDataType.OXYGEN_SATURATION to "SpO2",
    HealthDataType.BODY_TEMPERATURE to "Body Temp",
    HealthDataType.RESPIRATORY_RATE to "Resp. Rate",
    HealthDataType.LEAN_BODY_MASS to "Lean Mass",
    HealthDataType.ACTIVE_CALORIES to "Active Cal",
    HealthDataType.TOTAL_CALORIES to "Total Cal",
    HealthDataType.FLOORS_CLIMBED to "Floors",
    HealthDataType.EXERCISE to "Exercise",
)

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    var grantedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var sdkAvailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            sdkAvailable = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
            if (sdkAvailable) {
                val manager = HealthConnectManager(context)
                grantedPermissions = manager.getGrantedPermissions()
            }
        } catch (_: Exception) {
            sdkAvailable = false
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            "Permission Status",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Health Connect data type permissions for this app",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Open Settings button
        OutlinedButton(
            onClick = {
                try {
                    val intent = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS_SETTINGS")
                    intent.putExtra("android.intent.extra.PACKAGE_NAME", context.packageName)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    // Fallback: open Health Connect app directly
                    try {
                        val fallback = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
                        context.startActivity(fallback)
                    } catch (_: Exception) { }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Health Connect Settings")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (!sdkAvailable) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Health Connect Not Available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Install Health Connect to view permission status.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else {
            // Summary
            val totalRead = HealthDataType.entries.count { type ->
                HealthPermission.getReadPermission(type.recordClass) in grantedPermissions
            }
            val writableTypes = HealthDataType.entries.filter { !it.readOnly }
            val totalWrite = writableTypes.count { type ->
                try {
                    HealthPermission.getWritePermission(type.recordClass) in grantedPermissions
                } catch (_: Exception) { false }
            }
            val totalReadCount = HealthDataType.entries.size
            val totalWriteCount = writableTypes.size

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$totalRead / $totalReadCount",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Read",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$totalWrite / $totalWriteCount",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            "Write",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Column headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Data Type",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Read",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Write",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

            // Permission list
            val dataTypes = HealthDataType.entries.toList()
            val altRowColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(dataTypes) { index, dataType ->
                    val readPermission = HealthPermission.getReadPermission(dataType.recordClass)
                    val hasRead = readPermission in grantedPermissions
                    val hasWrite = if (dataType.readOnly) null else try {
                        HealthPermission.getWritePermission(dataType.recordClass) in grantedPermissions
                    } catch (_: Exception) { false }

                    val rowBackground = if (index % 2 == 1) altRowColor else Color.Transparent

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBackground)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            shortDisplayNames[dataType] ?: dataType.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                                PermissionIcon(granted = hasRead)
                            }
                            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                                PermissionIcon(granted = hasWrite)
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "— Some data types are read-only in Health Connect API",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionIcon(granted: Boolean?) {
    when (granted) {
        null -> Icon(
            imageVector = Icons.Filled.Remove,
            contentDescription = "Not available",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        true -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Granted",
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(22.dp)
        )
        false -> Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = "Denied",
            tint = Color(0xFFF44336),
            modifier = Modifier.size(22.dp)
        )
    }
}
