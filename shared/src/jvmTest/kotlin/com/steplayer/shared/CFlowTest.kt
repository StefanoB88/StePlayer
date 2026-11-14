package com.steplayer.shared

import com.steplayer.shared.util.wrap
import com.steplayer.shared.util.wrapOr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CFlowTest {
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
    private val main = mainExecutor.asCoroutineDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(main)
    @AfterTest fun tearDown() { Dispatchers.resetMain(); mainExecutor.shutdown() }

    @Test fun mappingRunsOffTheCollectorThread() = runBlocking {
        val mappedOn = Collections.synchronizedList(ArrayList<String>())
        val flow = flow { emit(1); emit(2) }.map { mappedOn += threadName(); it * 2 }.wrap()
        val collectedOn = ArrayList<String>()
        val values = withContext(main) { flow.toList().also { collectedOn += threadName() } }
        assertEquals(listOf(2, 4), values)
        assertEquals("fake-main", collectedOn.single())
        assertTrue(mappedOn.none { it == "fake-main" }, "mapping ran on $mappedOn")
    }

    @Test fun watchDeliversOnMainWhileMappingRunsElsewhere() = runBlocking {
        val mappedOn = Collections.synchronizedList(ArrayList<String>())
        val delivered = CompletableDeferred<Pair<Int, String>>()
        val handle = flow { emit(21) }.map { mappedOn += threadName(); it * 2 }.wrap()
            .watch { delivered.complete(it to threadName()) }
        val (value, thread) = withTimeout(5_000) { delivered.await() }
        handle.close()
        assertEquals(42, value)
        assertEquals("fake-main", thread)
        assertNotEquals("fake-main", mappedOn.single())
    }

    @Test fun equalValuesAreNotReEmitted() = runBlocking {
        val values = flow { emit(listOf(1)); emit(listOf(1)); emit(listOf(1, 2)); emit(listOf(1, 2)) }.wrap().toList()
        assertEquals(listOf(listOf(1), listOf(1, 2)), values)
    }

    @Test fun failingUpstreamNeverCrashesTheCollector() = runBlocking {
        var attempts = 0
        val failing = flow<List<Int>> { attempts++; throw IllegalStateException("database is locked") }
        assertEquals(emptyList(), failing.wrap().toList()) // completes quietly, after retries
        assertEquals(3, attempts)
        assertEquals(listOf(listOf(-1)), failing.wrapOr { listOf(-1) }.toList())

        // Swift side: neither a failing flow nor a throwing callback may escape the main scope.
        val calls = MutableSharedFlow<Int>(replay = 2)
        calls.emit(1); calls.emit(2)
        val seen = Collections.synchronizedList(ArrayList<Int>())
        val handle = calls.wrap().watch { seen += it; if (it == 1) error("bug in UI callback") }
        val failingHandle = failing.wrap().watch { }
        withTimeout(5_000) { while (seen.size < 2) delay(10) }
        handle.close(); failingHandle.close()
        assertEquals(listOf(1, 2), seen.toList())
        // The main dispatcher is still alive and working.
        assertEquals("fake-main", withContext(main) { threadName() })
        launch { }.join()
    }
}

/** Thread name without the " @coroutine#N" suffix added in coroutine debug mode. */
private fun threadName() = Thread.currentThread().name.substringBefore(" @")
