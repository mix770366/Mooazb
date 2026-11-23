package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.core.CoreStrings
import com.example.core.LocalAppLanguage
import com.example.core.LocalCoreStrings
import com.example.ui.components.EngineErrorBanner
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberSlate
import com.example.ui.theme.CyberSlateElevated
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SecurityEmerald
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "Auth"

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: (FirebaseUser?) -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    val hasFirebaseApp = try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context) != null
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }
    if (!hasFirebaseApp) {
        onUnauthenticated()
        return
    }
    Firebase.auth.currentUser?.let { existing ->
        onAuthSuccess(existing)
        return
    }
    val clientId = resolveDefaultWebClientId(context)
    if (clientId == null) {
        onUnauthenticated()
        return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
    scope.launch {
        try {
            val activityContext = context.findActivity() ?: context
            val cred = credentialManager.getCredential(activityContext, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = Firebase.auth.signInWithCredential(
                    GoogleAuthProvider.getCredential(token, null)
                ).await()
                val user = authResult.user ?: Firebase.auth.currentUser
                if (user != null) {
                    onAuthSuccess(user)
                } else {
                    onUnauthenticated()
                }
            } else {
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            onUnauthenticated()
        } catch (e: Exception) {
            Log.w(TAG, "Silent auto sign-in skipped: ${e::class.simpleName}: ${e.message}")
            onUnauthenticated()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("DiscouragedApi")
private fun resolveDefaultWebClientId(context: Context): String? {
    val resId = context.resources.getIdentifier(
        "default_web_client_id",
        "string",
        context.packageName
    )
    if (resId == 0) return null
    return try {
        context.getString(resId).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

private fun getAppSigningSha1(context: Context): String? {
    return try {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read signing SHA-1: ${e.message}")
        null
    }
}

private fun buildDiagnosticError(
    type: String,
    detail: String,
    sha1: String?
): String = buildString {
    append("[$type] ")
    append(detail)
    if (!sha1.isNullOrBlank()) {
        append("\nSHA-1: ")
        append(sha1)
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    strings: CoreStrings,
    onAuthSuccess: (FirebaseUser?) -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope
) {
    val clientId = resolveDefaultWebClientId(context)
    if (clientId == null) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found in resources.")
        return
    }
    val hasFirebaseApp = try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context) != null
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }
    if (!hasFirebaseApp) {
        onAuthError("FirebaseApp is not initialized. Verify app/google-services.json is configured.")
        return
    }
    val activityContext = context.findActivity()
    if (activityContext == null) {
        onAuthError("Google Sign-In requires an active Activity context.")
        return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()
    scope.launch {
        try {
            val cred = credentialManager.getCredential(activityContext, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = Firebase.auth.signInWithCredential(
                    GoogleAuthProvider.getCredential(token, null)
                ).await()
                val signedInUser = authResult.user ?: Firebase.auth.currentUser
                if (signedInUser != null) {
                    onAuthSuccess(signedInUser)
                } else {
                    onAuthError("Firebase Auth completed without returning a user session.")
                }
            } else {
                onAuthError("Unexpected credential type: ${cred.type}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            val sha1 = getAppSigningSha1(context)
            val detail = e.errorMessage?.toString()?.takeIf { it.isNotBlank() }
                ?: e.message?.takeIf { it.isNotBlank() }
                ?: "Cancelled or rejected by Google Play Services"
            val msg = buildString {
                append("${strings.authSignInFailedPrefix} ($detail).")
                append("\n${strings.authCancelledHint}")
                if (!sha1.isNullOrBlank()) {
                    append("\nSHA-1: ")
                    append(sha1)
                }
            }
            onAuthError(msg)
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credential available on device: ${e.message}", e)
            val sha1 = getAppSigningSha1(context)
            val detail = e.errorMessage?.toString()?.takeIf { it.isNotBlank() }
                ?: "No Google account credential found on this device. Please add a Google account in device Settings and try again."
            onAuthError(buildDiagnosticError("NoCredentialException", detail, sha1))
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error during Google Sign-In", e)
            val sha1 = getAppSigningSha1(context)
            val detail = e.errorMessage?.toString()?.takeIf { it.isNotBlank() }
                ?: e.localizedMessage
                ?: e.message
                ?: "Credential Manager error"
            onAuthError(buildDiagnosticError(e::class.simpleName ?: "GetCredentialException", detail, sha1))
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "Firebase Auth signInWithCredential failed (${e.errorCode})", e)
            val sha1 = getAppSigningSha1(context)
            val detail = "${e.errorCode}: ${e.localizedMessage ?: e.message ?: "Firebase Authentication failed"}"
            onAuthError(buildDiagnosticError("FirebaseAuthException", detail, sha1))
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            val sha1 = getAppSigningSha1(context)
            val detail = e.localizedMessage ?: e.message ?: "Sign in failed"
            onAuthError(buildDiagnosticError(e::class.simpleName ?: "AuthException", detail, sha1))
        }
    }
}

fun signOutUser(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}

@Composable
fun AuthScreen(
    onToggleLanguage: () -> Unit,
    onAuthSuccess: (FirebaseUser?) -> Unit,
    initialErrorMessage: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val strings = LocalCoreStrings.current
    val currentLanguage = LocalAppLanguage.current

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember(initialErrorMessage) { mutableStateOf(initialErrorMessage) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope
        )
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        ElectricCyan.copy(alpha = 0.14f),
                        ObsidianBg
                    )
                )
            )
            .safeDrawingPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.appTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    onClick = onToggleLanguage,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("auth_language_toggle"),
                    shape = RoundedCornerShape(50),
                    color = CyberSlateElevated,
                    border = BorderStroke(1.dp, CyberBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = currentLanguage.toggleLabel,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = currentLanguage.toggleLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                ElectricCyan.copy(alpha = 0.16f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_mooazb_logo),
                    contentDescription = strings.appTitle,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(190.dp)
                        .testTag("auth_brand_logo")
                )
            }

            Text(
                text = strings.signInTitle,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = strings.signInSubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AuthFeatureRow(Icons.Outlined.Psychology, strings.signInFeature1)
                AuthFeatureRow(Icons.Outlined.Shield, strings.signInFeature2)
                AuthFeatureRow(Icons.Outlined.Code, strings.signInFeature3)
            }

            errorMessage?.let { msg ->
                EngineErrorBanner(
                    message = msg,
                    showCopyAction = true,
                    onDismiss = { errorMessage = null }
                )
            }

            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    onGoogleSignInClicked(
                        context = context,
                        credentialManager = credentialManager,
                        strings = strings,
                        onAuthSuccess = { user ->
                            isLoading = false
                            onAuthSuccess(user)
                        },
                        onAuthError = { err ->
                            isLoading = false
                            errorMessage = err
                        },
                        scope = scope
                    )
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_sign_in_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = ObsidianBg
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = ObsidianBg,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = strings.signInButton,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthFeatureRow(icon: ImageVector, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = CyberSlate,
        border = BorderStroke(1.dp, CyberBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SecurityEmerald,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
