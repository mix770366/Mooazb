package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.core.AppLanguage
import com.example.core.ArabicStrings
import com.example.core.EnglishStrings
import com.example.core.LocalAppLanguage
import com.example.core.LocalCoreStrings
import com.example.domain.ReasoningLevel
import com.example.ui.auth.AuthScreen
import com.example.ui.components.ReasoningTierSegmentedControl
import com.example.ui.components.TokenCapacityGauge
import com.example.ui.screens.ChatScreen
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.theme.CoreMindTheme
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.cyberPaletteForTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string resources from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        val dbId = context.getString(R.string.firestore_database_id)
        assertEquals("Mooazb AI", appName)
        assertTrue(dbId.isNotBlank())
    }

    @Test
    fun `bilingual localization and layout directions are consistent`() {
        assertEquals(LayoutDirection.Rtl, AppLanguage.AR.layoutDirection)
        assertEquals(LayoutDirection.Ltr, AppLanguage.EN.layoutDirection)
        assertTrue(ArabicStrings.appTitle.isNotBlank())
        assertTrue(EnglishStrings.appTitle.isNotBlank())
        assertTrue(ArabicStrings.ideHeaderTitle.isNotBlank())
        assertTrue(EnglishStrings.ideHeaderTitle.isNotBlank())
    }

    @Test
    fun `reasoning segmented control switches across all 4 tiers`() {
        var selected by mutableStateOf(ReasoningLevel.HIGH)

        composeTestRule.setContent {
            CoreMindTheme {
                ReasoningTierSegmentedControl(
                    selectedLevel = selected,
                    onSelect = { selected = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("reasoning_segment_high").assertIsSelected()

        composeTestRule.onNodeWithTag("reasoning_segment_low").performClick()
        composeTestRule.onNodeWithTag("reasoning_segment_low").assertIsSelected()
        assertEquals(ReasoningLevel.LOW, selected)

        composeTestRule.onNodeWithTag("reasoning_segment_medium").performClick()
        composeTestRule.onNodeWithTag("reasoning_segment_medium").assertIsSelected()
        assertEquals(ReasoningLevel.MEDIUM, selected)

        composeTestRule.onNodeWithTag("reasoning_segment_xhigh").performClick()
        composeTestRule.onNodeWithTag("reasoning_segment_xhigh").assertIsSelected()
        assertEquals(ReasoningLevel.XHIGH, selected)
    }

    @Test
    fun `animated cybernetic lab theme transitions smoothly across all 4 reasoning tiers`() {
        val palettes = ReasoningLevel.entries.map { cyberPaletteForTier(it) }
        assertEquals(4, palettes.map { it.accent }.toSet().size)
        assertEquals(4, palettes.map { it.background }.toSet().size)

        var activeTier by mutableStateOf(ReasoningLevel.LOW)
        var observedPrimary = Color.Unspecified
        var observedSurface = Color.Unspecified

        composeTestRule.setContent {
            CoreMindTheme(reasoningLevel = activeTier) {
                observedPrimary = MaterialTheme.colorScheme.primary
                observedSurface = LocalCyberPalette.current.surface
                ReasoningTierSegmentedControl(
                    selectedLevel = activeTier,
                    onSelect = { activeTier = it }
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(cyberPaletteForTier(ReasoningLevel.LOW).accent, observedPrimary)
        assertEquals(cyberPaletteForTier(ReasoningLevel.LOW).surface, observedSurface)

        composeTestRule.onNodeWithTag("reasoning_segment_xhigh").performClick()
        composeTestRule.mainClock.advanceTimeBy(600L)
        composeTestRule.waitForIdle()

        assertEquals(ReasoningLevel.XHIGH, activeTier)
        assertEquals(cyberPaletteForTier(ReasoningLevel.XHIGH).accent, observedPrimary)
        assertEquals(cyberPaletteForTier(ReasoningLevel.XHIGH).surface, observedSurface)
        assertNotEquals(
            cyberPaletteForTier(ReasoningLevel.LOW).accent,
            observedPrimary
        )
    }

    @Test
    fun `manifest declares microphone permission without broad storage permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(permissions.contains(Manifest.permission.INTERNET))
        assertTrue(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE))
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(permissions.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(permissions.contains("android.permission.READ_MEDIA_VIDEO"))
    }

    @Test
    fun `auth screen displays diagnostic error banner with copy action when sign-in fails`() {
        val diagnostic = "[GetCredentialCancellationException] SHA-1 mismatch\nSHA-1: 51:8D:04:1F"
        composeTestRule.setContent {
            CoreMindTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.AR,
                    LocalCoreStrings provides ArabicStrings
                ) {
                    AuthScreen(
                        onToggleLanguage = {},
                        onAuthSuccess = {},
                        initialErrorMessage = diagnostic
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_brand_logo").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("engine_error_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_banner_copy_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("51:8D:04:1F", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("error_banner_dismiss_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("engine_error_banner").assertDoesNotExist()
    }

    @Test
    fun `token capacity gauge displays counter and badge on single lines in Arabic RTL`() {
        composeTestRule.setContent {
            CoreMindTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.AR,
                    LocalCoreStrings provides ArabicStrings,
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    TokenCapacityGauge(usedTokens = 356L)
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("token_capacity_counter").assertIsDisplayed()
        composeTestRule.onNodeWithText("356 / 1,000,000").assertIsDisplayed()
        composeTestRule.onNodeWithText(ArabicStrings.implicitCacheBadge).assertIsDisplayed()
    }

    @Test
    fun `chat screen displays empty starter suggestions and composer controls`() {
        val chatViewModel = ChatViewModel()
        composeTestRule.setContent {
            CoreMindTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.EN,
                    LocalCoreStrings provides EnglishStrings,
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    ChatScreen(viewModel = chatViewModel)
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("chat_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_suggestion_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_message_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_clear_button").assertIsDisplayed()
    }
}
