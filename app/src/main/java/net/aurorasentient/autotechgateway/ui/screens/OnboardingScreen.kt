package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * First-launch onboarding flow.
 * Shows 4 pages: Welcome/Brand → AI Diagnostics → Connect Adapter → Get Started
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> AIDiagnosticsPage()
                2 -> ConnectAdapterPage()
                3 -> GetStartedPage(onComplete = onComplete)
            }
        }

        // Bottom navigation: dots + Next/Get Started button
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, DarkBackground),
                        startY = 0f,
                        endY = 80f
                    )
                )
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp, top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page indicator dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) AutotechBlue
                                else TextSecondary.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip button (hidden on last page)
                if (pagerState.currentPage < 3) {
                    TextButton(onClick = onComplete) {
                        Text(
                            "Skip",
                            color = TextSecondary,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(64.dp))
                }

                // Next / Get Started button
                if (pagerState.currentPage < 3) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AutotechBlue
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Next", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusGreen
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .fillMaxWidth(0.7f)
                    ) {
                        Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Get Started", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ========== Page 1: Welcome / Brand ==========

@Composable
private fun WelcomePage() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 80.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated logo icon
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AutotechBlue,
                            AutotechBlueDark
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            "Autotech AI",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "World-Class Vehicle Diagnostics",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = AutotechBlueLight,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Your scan tool reads the codes.\nOurs tells you what's actually wrong.",
            fontSize = 16.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

// ========== Page 2: AI-Powered Diagnostics ==========

@Composable
private fun AIDiagnosticsPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 60.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Psychology,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = AutotechBlue
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "AI-Powered Diagnostics",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Feature list
        val features = listOf(
            FeatureItem(
                icon = Icons.Default.Analytics,
                title = "Root Cause Analysis",
                description = "AI correlates DTCs, freeze frame, Mode 6, and live data to pinpoint the real problem"
            ),
            FeatureItem(
                icon = Icons.Default.MenuBook,
                title = "Repair Guidance",
                description = "Access OEM repair procedures, TSBs, wiring diagrams, and YouTube walkthroughs"
            ),
            FeatureItem(
                icon = Icons.Default.Speed,
                title = "Advanced Monitoring",
                description = "Real-time PIDs, Mode 6 monitor tests, enhanced manufacturer-specific data"
            ),
            FeatureItem(
                icon = Icons.Default.Security,
                title = "Recall & Safety Alerts",
                description = "Automatic NHTSA recall, complaint, and investigation lookup for your vehicle"
            )
        )

        features.forEach { feature ->
            FeatureRow(feature)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private data class FeatureItem(
    val icon: ImageVector,
    val title: String,
    val description: String
)

@Composable
private fun FeatureRow(feature: FeatureItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AutotechBlue.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                feature.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = AutotechBlue
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                feature.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                feature.description,
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

// ========== Page 3: Connect Your Adapter ==========

@Composable
private fun ConnectAdapterPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 60.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.BluetoothConnected,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = AutotechBlue
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Connect Your Adapter",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Step-by-step instructions
        val steps = listOf(
            "Plug the OBDLink MX+ into your vehicle's OBD-II port (under the dashboard)",
            "Pair via Bluetooth in your phone's Settings",
            "Open this app and tap \"Scan for Adapters\" on the Dashboard",
            "Select your adapter to connect — the app handles the rest"
        )

        steps.forEachIndexed { index, step ->
            StepRow(number = index + 1, text = step)
            if (index < steps.lastIndex) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Supported adapters note
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = AutotechBlueDark.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = AutotechBlueLight,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Designed for OBDLink MX+ with full HS-CAN, MS-CAN, and SW-CAN/GMLAN support. Also compatible with standard ELM327 adapters.",
                    fontSize = 13.sp,
                    color = AutotechBlueLight,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AutotechBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$number",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text,
            fontSize = 15.sp,
            color = TextPrimary,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

// ========== Page 4: Get Started — Free Tokens ==========

@Composable
private fun GetStartedPage(onComplete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 60.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CardGiftcard,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = StatusGreen
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Start Diagnosing",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Free tokens badge
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = StatusGreen.copy(alpha = 0.15f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "50,000",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusGreen
                )
                Text(
                    "FREE TOKENS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusGreen,
                    letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Enough for 5-10 full diagnostic sessions",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // What you get
        val perks = listOf(
            "AI-powered root cause analysis" to Icons.Default.Psychology,
            "OEM repair procedures & TSBs" to Icons.Default.MenuBook,
            "NHTSA recall & safety alerts" to Icons.Default.Security,
            "Enhanced manufacturer diagnostics" to Icons.Default.Build
        )

        perks.forEach { (text, icon) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = StatusGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Visit automotive.aurora-sentient.net to create your account and start diagnosing vehicles with AI.",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}
