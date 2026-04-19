package com.mezon.mobile.qr

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmTransferScreen(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Confirm Transfer")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onConfirm) {
            Text("Confirm")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

