package com.mezon.mobile.qr

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmLoginScreen(
    isSuccess: Boolean,
    title: String,
    warning: String,
    successMessage: String,
    confirmLabel: String,
    cancelLabel: String,
    startLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onStartTalking: () -> Unit
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title)
        Spacer(modifier = Modifier.height(16.dp))
        if (isSuccess) {
            Text(text = successMessage)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onStartTalking) {
                Text(startLabel)
            }
        } else {
            Text(text = warning)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onConfirm) {
                Text(confirmLabel)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onCancel) {
                Text(cancelLabel)
            }
        }
    }
}

