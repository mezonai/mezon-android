package com.mezon.mobile.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ── Palette ──────────────────────────────────────────────────────────────────
private val DarkBg      = Color(0xFF160828)
private val CardBg      = Color(0xFF2A0E3F)
private val TabBg       = Color(0xFF2A0E3F)
private val TabActiveBg = Color(0xFF3D1458)
private val MezonRing   = Color(0xFFCC44EE)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFF9B8FAF)

@Composable
fun MyQrScreen(
    state: MyQrState,
    onTabChanged: (MyQrTab) -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    val selectedTab = state.activeTab

    Column(
        Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Text(
                text = "My QR Code",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Tab pill ─────────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(44.dp)
                .background(TabBg, RoundedCornerShape(22.dp))
                .padding(4.dp)
        ) {
            Row(Modifier.fillMaxSize()) {
                listOf(MyQrTab.PROFILE to "QR Profile", MyQrTab.TRANSFER to "QR Transfer").forEach { (tab, label) ->
                    val isActive = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (isActive) TabActiveBg else Color.Transparent,
                                RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = { onTabChanged(tab) },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isActive) TextPrimary else TextSecondary,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── User info card ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = state.userInfo.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = state.userInfo.username,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (selectedTab == MyQrTab.PROFILE) "Share with others"
                           else "Balance: ${state.walletBalance}",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── QR white card ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color.White, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mezon logo: purple circle ring + text
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .border(3.dp, MezonRing, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Mezon",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // QR code with avatar in center
            val qrBitmap = if (selectedTab == MyQrTab.PROFILE) state.qrProfileBitmap
                           else state.qrTransferBitmap
            if (qrBitmap != null) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(230.dp)
                    )
                    // Avatar overlay in center of QR
                    Box(
                        Modifier
                            .size(50.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(3.dp)
                    ) {
                        AsyncImage(
                            model = state.userInfo.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else {
                // Placeholder while loading
                Box(
                    Modifier
                        .size(230.dp)
                        .background(Color(0xFFF0F0F0), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MezonRing, modifier = Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Divider + powered by
            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Powered by Mezon",
                color = Color(0xFF777777),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Download / Share icon buttons (Profile tab only) ──────────────────
        if (selectedTab == MyQrTab.PROFILE) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .size(54.dp)
                        .background(CardBg, RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(54.dp)
                        .background(CardBg, RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Description
        Text(
            text = if (selectedTab == MyQrTab.PROFILE)
                "Scan this QR code to chat with me or view my profile"
            else
                "Scan this QR code to transfer funds",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )

        Spacer(Modifier.height(32.dp))
    }
}

// ── CustomQrInvite - used for rendering share/download bitmap ─────────────────
@Composable
fun CustomQrInvite(state: MyQrState, qrBitmap: android.graphics.Bitmap) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .border(2.5.dp, MezonRing, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text("Mezon", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
        }
        Spacer(Modifier.height(16.dp))
        Box(contentAlignment = Alignment.Center) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(200.dp)
            )
            Box(
                Modifier
                    .size(46.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                AsyncImage(
                    model = state.userInfo.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color(0xFFEEEEEE))
        Spacer(Modifier.height(10.dp))
        Text("Powered by Mezon", fontSize = 13.sp, color = Color(0xFF777777))
    }
}
