package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.JarvisVoiceInput
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Jarvis", appName)
  }

  @Test
  fun `voice input onRmsChanged triggers callback without recursion`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var receivedRms = -1f
    val voiceInput = JarvisVoiceInput(
      context = context,
      onListeningChanged = {},
      onRmsChanged = { rms -> receivedRms = rms },
      onSpeechResult = {},
      onPartialResult = {},
      onErrorOccurred = {}
    )

    val listener = voiceInput.createListener()
    // Test that onRmsChanged does not cause StackOverflowError and normalizes correctly
    listener.onRmsChanged(4.0f)

    // ((4.0 + 2.0) / 12.0) = 6.0 / 12.0 = 0.5
    assertEquals(0.5f, receivedRms, 0.001f)
  }
}
