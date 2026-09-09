package com.heyheyon.armbandbot

import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Only called by offline disposable fixtures, before their UI/data cleanup. */
internal fun saveBeta4UiEvidence(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val run = InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
    require(run.matches(Regex("[A-Za-z0-9_-]+")))
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "beta4-ui-evidence/$run/api${Build.VERSION.SDK_INT}").apply { check(mkdirs() || isDirectory) }
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(150L, 5_000L)
    var screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
    var stable = false
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        instrumentation.uiAutomation.waitForIdle(120L, 5_000L)
        val next = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        if (screenshot.sameAs(next)) { next.recycle(); stable = true; break }
        screenshot.recycle(); screenshot = next
    }
    val safeName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
    val file = File(directory, "$safeName.png")
    try { file.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
    finally { screenshot.recycle() }
    // Gradle may uninstall the fixture APK immediately after JUnit finishes.
    // Preserve the pixels in this disposable guest's shell-owned evidence directory first.
    val shellDirectory = "/data/local/tmp/armbandbot_beta4_evidence/$run/api${Build.VERSION.SDK_INT}"
    fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    // executeShellCommand is not a shell expression parser: issue separate simple commands.
    shell("mkdir -p $shellDirectory")
    val destination = "$shellDirectory/${file.name}"
    shell("cp ${file.absolutePath} $destination")
    check(shell("stat -c%s $destination").toLongOrNull() == file.length()) { "Unable to preserve fixture screenshot" }
    check(stable) { "Captured pixels did not settle before the screenshot deadline" }
}
