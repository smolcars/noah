package com.noahwallet.services

import com.noahwallet.services.logging.*
import io.github.niteshbalusu11.bark.LogLevel
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A distinct instrumentation configuration isolates the process-lifetime fail-closed marker.
@Config(sdk = [35], application = android.app.Application::class,
    instrumentedPackages = ["com.noahwallet.services.logging"])
class EndpointRecordPrivacyTest {
    private fun cases(kind: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-records.json")!!.readText()).jsonArray
        .map { it.jsonObject }.filter { it.getValue("kind").jsonPrimitive.content == kind }

    @Test fun deviceIcuUsesSharedIdna2008Tokens() {
        val tokens = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-tokens.json")!!.readText()).jsonArray.map { it.jsonObject }
        for (test in tokens.filter { it.getValue("name").jsonPrimitive.content.startsWith("IDNA2008") }) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            assertEquals(test.getValue("name").jsonPrimitive.content, test.getValue("expected").jsonPrimitive.content,
                LogFile.redact(test.getValue("input").jsonPrimitive.content))
        }
    }

    @Test fun formattingCannotRebuildTokensAtFileLogcatAndTelemetrySinks() {
        for (kind in listOf("record", "bark")) for (test in cases(kind)) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            val directory = Files.createTempDirectory("record-privacy").toFile()
            val file = LogFile(directory, executor = Executor { it.run() })
            val tag = test.getValue("tag").jsonPrimitive.content
            val message = test.getValue("message").jsonPrimitive.content
            val secret = test.getValue("secret").jsonPrimitive.content
            try {
                ShadowLog.clear()
                if (kind == "bark") BarkLog(file).log(LogLevel.ERROR, tag, message) else AppLog(tag, file).e(message)
                file.flush()
                assertFalse(test.toString(), directory.resolve("noah.log").readText().contains(secret))
                assertFalse(ShadowLog.getLogs().joinToString { it.msg }.contains(secret))
                assertFalse(file.lines().joinToString("\n").contains(secret))
                val formatted = "[$tag] $message"
                val event = SentryEvent().apply { this.message = Message().apply { this.formatted = formatted } }
                assertFalse(requireNotNull(AppTelemetry.scrub(event)).message!!.formatted!!.contains(secret))
                assertFalse(requireNotNull(AppTelemetry.scrub(Breadcrumb().apply { this.message = formatted })).message!!.contains(secret))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun generatedOverflowRecordsPassTheFinalBoundary() {
        for (test in cases("overflow")) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            val directory = Files.createTempDirectory("overflow-privacy").toFile()
            val tasks = ArrayDeque<Runnable>()
            val file = LogFile(directory, capacity = 1, executor = Executor(tasks::addLast))
            try {
                repeat(test.getValue("count").jsonPrimitive.int + 1) { file.append(AppLog.Level.info, "test", "ordinary record") }
                while (tasks.isNotEmpty()) tasks.removeFirst().run()
                val raw = directory.resolve("noah.log").readText()
                assertFalse(raw.contains(test.getValue("secret").jsonPrimitive.content))
                assertTrue(raw.contains("[custom-esplora]"))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun widgetErrorsUseTheLoggingBoundaryWithoutRawThrowables() {
        val vectors = Json.parseToJsonElement(javaClass.classLoader!!.getResource("esplora-log-privacy-r9.json")!!.readText()).jsonArray.map { it.jsonObject }
        for (test in vectors.filter { it.getValue("kind").jsonPrimitive.content == "widget" }) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val context = object : android.content.ContextWrapper(app) {
                override fun getPackageName(): String = throw IllegalStateException("fake raw throwable detail")
            }
            val method = com.noahwallet.widgets.NoahWidgetProvider::class.java.getDeclaredMethod("updateWidget",
                android.content.Context::class.java, android.appwidget.AppWidgetManager::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            ShadowLog.clear()
            method.invoke(com.noahwallet.widgets.NoahWidgetProvider(), context, android.appwidget.AppWidgetManager.getInstance(app), 1)
            LogFile.shared(app).flush()
            val logs = ShadowLog.getLogs().filter { it.type == android.util.Log.ERROR }
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.all { it.throwable == null })
            val text = logs.joinToString { it.msg }
            assertFalse(text.contains(test.getValue("secret").jsonPrimitive.content))
            assertFalse(text.contains("fake raw throwable detail"))
            assertTrue(text.contains("[custom-esplora]"))
        }
    }

    @Test fun fallbackTypeNamesUseThePrivacyBoundary() {
        val error = SocketTimeoutException("fake unrelated detail")
        assertEquals("SocketTimeoutException", SafeErrorDescription.of(error))
        assertEquals("IllegalStateException", SafeErrorDescription.of(IllegalStateException()))
        for (test in cases("fallback").filter { it.getValue("type").jsonPrimitive.content == "SocketTimeoutException" }) {
            EndpointLogPrivacy.register(test.getValue("endpoint").jsonPrimitive.content)
            assertEquals("[custom-esplora]", SafeErrorDescription.of(error))
            assertEquals("IllegalStateException", SafeErrorDescription.of(IllegalStateException()))
        }
    }
}
