package org.snippet.runner.run

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.snippet.runner.printscript.PrintScriptRunner
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs real PrintScript on its own thread and looks at the events as a client would get them. */
class InteractiveRunTest {
    private val runners = mutableListOf<InteractiveRunner>()
    private val events = LinkedBlockingQueue<RunEvent>()

    @AfterEach
    fun shutdown() = runners.forEach { it.shutdown() }

    private fun run(limits: InteractiveRunProperties = InteractiveRunProperties()): InteractiveRun {
        val runner = InteractiveRunner(PrintScriptRunner(), limits).also { runners += it }
        return runner.create { events.put(it) }
    }

    private fun next(): RunEvent = events.poll(5, TimeUnit.SECONDS) ?: error("no event in 5 s")

    private fun nothingElse() = assertNull(events.poll(200, TimeUnit.MILLISECONDS))

    @Test
    fun `outputs arrive one by one and input is asked for lazily`() {
        val run = run()
        run.start("println(\"hi\");\nlet name: string = readInput(\"Name?\");\nprintln(\"Hello \" + name);", "1.1", "c-1")

        assertEquals(RunEvent.Output("hi"), next())
        assertEquals(RunEvent.InputRequest("Name?"), next())
        // The program is blocked until the input arrives
        nothingElse()
        run.input("Ada")
        assertEquals(RunEvent.Output("Hello Ada"), next())
        assertEquals(RunEvent.Done(DoneStatus.OK), next())
        nothingElse()
    }

    @Test
    fun `input that nobody asked for is ignored`() {
        val run = run()
        run.input("too early")
        run.start("let a: string = readInput(\"a\");\nprintln(a);", "1.1", "c-1")

        assertEquals(RunEvent.InputRequest("a"), next())
        run.input("right")
        assertEquals(RunEvent.Output("right"), next())
        assertEquals(RunEvent.Done(DoneStatus.OK), next())
    }

    @Test
    fun `invalid code is an error with diagnostics, then done error`() {
        run().start("println(\"x\");\nlet y: number = ;", "1.0", "c-1")

        val error = next() as RunEvent.Error
        assertEquals(2, error.diagnostics.single().line)
        assertEquals(RunEvent.Done(DoneStatus.ERROR), next())
    }

    @Test
    fun `an unsupported version is an error`() {
        run().start("println(1);", "9.9", "c-1")

        assertTrue((next() as RunEvent.Error).message.contains("9.9"))
        assertEquals(RunEvent.Done(DoneStatus.ERROR), next())
    }

    @Test
    fun `stop while waiting for input ends the run as stopped`() {
        val run = run()
        run.start("let a: string = readInput(\"a\");\nprintln(a);", "1.1", "c-1")
        assertEquals(RunEvent.InputRequest("a"), next())

        run.stop()

        assertEquals(RunEvent.Done(DoneStatus.STOPPED), next())
        run.input("late")
        nothingElse()
        assertTrue(run.isFinished)
    }

    @Test
    fun `a run can only be started once`() {
        val run = run()
        assertTrue(run.start("println(1);", "1.0", "c-1"))
        assertFalse(run.start("println(2);", "1.0", "c-1"))

        assertEquals(RunEvent.Output("1"), next())
        assertEquals(RunEvent.Done(DoneStatus.OK), next())
        nothingElse()
    }

    @Test
    fun `waiting too long for input is an error`() {
        run(InteractiveRunProperties(inputTimeout = Duration.ofMillis(200)))
            .start("let a: string = readInput(\"a\");", "1.1", "c-1")

        assertEquals(RunEvent.InputRequest("a"), next())
        assertTrue((next() as RunEvent.Error).message.contains("input"))
        assertEquals(RunEvent.Done(DoneStatus.ERROR), next())
    }

    @Test
    fun `a run that goes past the max duration is cut`() {
        run(InteractiveRunProperties(maxDuration = Duration.ofMillis(300)))
            .start("let a: string = readInput(\"a\");", "1.1", "c-1")

        assertEquals(RunEvent.InputRequest("a"), next())
        assertTrue((next() as RunEvent.Error).message.contains("longer"))
        assertEquals(RunEvent.Done(DoneStatus.ERROR), next())
        nothingElse()
    }

    @Test
    fun `output past the line cap ends the run`() {
        run(InteractiveRunProperties(maxOutputs = 2)).start("println(1);\nprintln(2);\nprintln(3);\nprintln(4);", "1.0", "c-1")

        assertEquals(RunEvent.Output("1"), next())
        assertEquals(RunEvent.Output("2"), next())
        assertTrue((next() as RunEvent.Error).message.contains("limit"))
        assertEquals(RunEvent.Done(DoneStatus.ERROR), next())
        nothingElse()
    }

    @Test
    fun `output past the size cap ends the run`() {
        run(InteractiveRunProperties(maxOutputSize = DataSize.ofBytes(8))).start("println(\"12345\");\nprintln(\"67890\");", "1.0", "c-1")

        assertEquals(RunEvent.Output("12345"), next())
        assertTrue(next() is RunEvent.Error)
        assertEquals(RunEvent.Done(DoneStatus.ERROR), next())
    }
}
