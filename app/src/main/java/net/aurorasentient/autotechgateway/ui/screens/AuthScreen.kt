package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * Combined Login / Sign Up screen.
 * Gates all app functionality behind authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, name: String, password: String) -> Unit,
    onClearError: () -> Unit
) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    val displayError = errorMessage ?: localError

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Logo / Brand
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                tint = AutotechBlue,
                modifier = Modifier.size(64.dp)
            )

            Text(
                "Autotech AI",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                "Professional Vehicle Diagnostics",
                fontSize = 14.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sign In / Sign Up tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilterChip(
                    selected = !isSignUp,
                    onClick = {
                        isSignUp = false
                        localError = null
                        onClearError()
                    },
                    label = { Text("Sign In") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AutotechBlue,
                        selectedLabelColor = TextPrimary
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
                FilterChip(
                    selected = isSignUp,
                    onClick = {
                        isSignUp = true
                        localError = null
                        onClearError()
                    },
                    label = { Text("Create Account") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AutotechBlue,
                        selectedLabelColor = TextPrimary
                    )
                )
            }

            // Error message
            AnimatedVisibility(visible = displayError != null) {
                Surface(
                    color = StatusRed.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = StatusRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            displayError ?: "",
                            color = StatusRed,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Email field
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; localError = null; onClearError() },
                label = { Text("Email") },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = if (isSignUp) ImeAction.Next else ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = autotechTextFieldColors()
            )

            // Name field (sign up only)
            AnimatedVisibility(visible = isSignUp) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; localError = null },
                    label = { Text("Full Name") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = autotechTextFieldColors()
                )
            }

            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; localError = null; onClearError() },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isSignUp) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = { if (isSignUp) focusManager.moveFocus(FocusDirection.Down) },
                    onDone = {
                        if (!isSignUp) {
                            focusManager.clearFocus()
                            if (email.isNotBlank() && password.isNotBlank()) {
                                onSignIn(email, password)
                            }
                        }
                    }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = autotechTextFieldColors()
            )

            // Confirm password (sign up only)
            AnimatedVisibility(visible = isSignUp) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; localError = null },
                    label = { Text("Confirm Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = autotechTextFieldColors()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Submit button
            Button(
                onClick = {
                    localError = null
                    onClearError()

                    if (email.isBlank()) {
                        localError = "Please enter your email address"
                        return@Button
                    }
                    if (password.isBlank()) {
                        localError = "Please enter your password"
                        return@Button
                    }
                    if (password.length < 8) {
                        localError = "Password must be at least 8 characters"
                        return@Button
                    }

                    if (isSignUp) {
                        if (name.isBlank()) {
                            localError = "Please enter your name"
                            return@Button
                        }
                        if (password != confirmPassword) {
                            localError = "Passwords don't match"
                            return@Button
                        }
                        onSignUp(email, name, password)
                    } else {
                        onSignIn(email, password)
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (isSignUp) "Create Account" else "Sign In",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Footer
            Text(
                "By signing in, you agree to the\nAutotech AI Terms of Service",
                fontSize = 11.sp,
                color = TextSecondary.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Consistent text field colors matching the dark theme.
 */
@Composable
private fun autotechTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AutotechBlue,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
    focusedLabelColor = AutotechBlue,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AutotechBlue,
    focusedLeadingIconColor = AutotechBlue,
    unfocusedLeadingIconColor = TextSecondary,
    focusedTrailingIconColor = TextSecondary,
    unfocusedTrailingIconColor = TextSecondary
)
