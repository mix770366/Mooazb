package com.example.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import java.util.UUID

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val CUSTOM_PREFS = "com.example.appcheck.custom"
private const val CUSTOM_TOKEN_KEY = "custom_debug_token"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Suppress("DiscouragedApi")
private fun resolveRecaptchaSiteKey(context: Context): String? {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
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

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val debugPrefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            val customPrefs = context.getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE)

            val intentToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()
            val savedCustomToken = customPrefs.getString(CUSTOM_TOKEN_KEY, null)?.trim()

            when {
                !intentToken.isNullOrEmpty() -> {
                    // MUST use commit() synchronously before DebugAppCheckProviderFactory reads it.
                    debugPrefs.edit()
                        .putString(DEBUG_SECRET_KEY, intentToken)
                        .commit()
                    Log.d(TAG, "App Check debug secret configured from launch Intent.")
                }
                !savedCustomToken.isNullOrEmpty() -> {
                    debugPrefs.edit()
                        .putString(DEBUG_SECRET_KEY, savedCustomToken)
                        .commit()
                    Log.d(TAG, "App Check debug secret loaded from saved user token.")
                }
                debugPrefs.getString(DEBUG_SECRET_KEY, null).isNullOrEmpty() -> {
                    // Pre-create and persist a deterministic device debug UUID synchronously
                    // so the user can view/copy it in-app without needing ADB Logcat.
                    val generated = UUID.randomUUID().toString()
                    debugPrefs.edit()
                        .putString(DEBUG_SECRET_KEY, generated)
                        .commit()
                    Log.w(TAG, "Generated local App Check debug secret: $generated")
                }
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val siteKey = resolveRecaptchaSiteKey(context)
            if (!siteKey.isNullOrBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found or blank.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

fun readLocalAppCheckDebugSecret(context: Context): String? {
    if (!BuildConfig.DEBUG) return null
    return try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        debugPrefs.getString(DEBUG_SECRET_KEY, null)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

fun saveAndApplyCustomAppCheckDebugSecret(context: Context, token: String): Boolean {
    val clean = token.trim()
    if (!BuildConfig.DEBUG || clean.isEmpty()) return false
    return try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val customPrefs = context.getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE)
        customPrefs.edit().putString(CUSTOM_TOKEN_KEY, clean).commit()

        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val debugPrefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        debugPrefs.edit().putString(DEBUG_SECRET_KEY, clean).commit()
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to apply custom App Check debug secret", e)
        false
    }
}

fun isAppCheckErrorMessage(message: String): Boolean {
    return message.contains("App Check", ignoreCase = true) ||
        message.contains("attestation", ignoreCase = true) ||
        message.contains("403", ignoreCase = true) ||
        message.contains("AppCheck", ignoreCase = true)
}

fun firebaseAppCheckConsoleUrl(context: Context): String {
    val projectId = try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        FirebaseApp.getInstance().options.projectId
    } catch (_: Exception) {
        null
    }
    return if (!projectId.isNullOrBlank()) {
        "https://console.firebase.google.com/project/$projectId/appcheck/apps"
    } else {
        "https://console.firebase.google.com/"
    }
}
