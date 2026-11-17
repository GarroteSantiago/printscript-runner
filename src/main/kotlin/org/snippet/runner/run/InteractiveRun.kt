package org.snippet.runner.run

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.snippet.runner.common.logging.CorrelationIdFilter
import org.snippet.runner.printscript.Diagnostic
import org.snippet.runner.printscript.PrintScriptRunner
import org.snippet.runner.printscript.ProgramIo
import org.snippet.runner.printscript.RunAbortedException
import org.snippet.runner.printscript.RunResult
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Limits of an interactive run, so a program can't hold a thread or flood the socket forever. */
@ConfigurationProperties("interactive-run")
data class InteractiveRunProperties(
    /** The whole run, including the time spent waiting for input. */
    val maxDuration: Duration = Duration.ofSeconds(60),
    /** Waiting for one `input` after an `input-request`. */
    val inputTimeout: Duration = Duration.ofMinutes(5),
    /** `output` messages per run. */
    val maxOutputs: Int = 10_000,
    /** Characters of output per run (UTF-8 bytes). */
    val maxOutputSize: DataSize = DataSize.ofMegabytes(1),
)

enum class DoneStatus(
    val value: String,
) {
    OK("ok"),
    ERROR("error"),
    STOPPED("stopped"),
}

/** What a run tells the client, in the order it happens. [Done] is always the last one. */
sealed interface RunEvent {
    data class Output(
        val value: String,
    ) : RunEvent

    data class InputRequest(
        val prompt: String,
    ) : RunEvent

    data class Error(
        val message: String,
        val diagnostics: List<Diagnostic> = emptyList(),
    ) : RunEvent

    data class Done(
        val status: DoneStatus,
    ) : RunEvent
}

/** Creates runs and owns the timer that cuts the ones that take too long. */
@Component
class InteractiveRunner(
    private val runner: PrintScriptRunner,
    private val properties: InteractiveRunProperties,
) {
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            Thread
                .ofPlatform()
                .name("run-timer")
                .daemon()
                .factory(),
        )

    /** [events] is called from the run's thread and from the timer, never at the same time. */
    fun create(events: (RunEvent) -> Unit) = InteractiveRun(runner, properties, timer, events)

    @PreDestroy
    fun shutdown() {
        timer.shutdownNow()
    }
}

/**
 * One execution of a program, on its own virtual thread: every `println` becomes an
 * [RunEvent.Output] right away, every `readInput` an [RunEvent.InputRequest] that blocks the
 * program until [input] is called. It ends with exactly one [RunEvent.Done]; after that nothing
 * else is emitted, even if the program's thread is still unwinding.
 */
class InteractiveRun internal constructor(
    private val runner: PrintScriptRunner,
    private val limits: InteractiveRunProperties,
    private val timer: ScheduledExecutorService,
    private val events: (RunEvent) -> Unit,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean()
    private val lock = Any()
    private val inputs = LinkedBlockingQueue<String>()

    @Volatile private var finished = false

    @Volatile private var awaitingInput = false

    @Volatile private var thread: Thread? = null
    private var deadline: ScheduledFuture<*>? = null

    // Only touched by the program's thread
    private var outputs = 0
    private var outputBytes = 0L

    val isFinished get() = finished

    /** Starts the program; false (and nothing happens) if this run was already started. */
    fun start(
        content: String,
        version: String,
        correlationId: String,
    ): Boolean {
        if (!started.compareAndSet(false, true)) return false
        synchronized(lock) {
            deadline =
                timer.schedule(
                    { abort(RunEvent.Error("The run took longer than ${limits.maxDuration.seconds} s"), DoneStatus.ERROR) },
                    limits.maxDuration.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            thread =
                Thread.ofVirtual().name("run-$correlationId").start {
                    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId)
                    try {
                        execute(content, version)
                    } finally {
                        MDC.remove(CorrelationIdFilter.MDC_KEY)
                    }
                }
        }
        return true
    }

    /** The answer to the pending `input-request`. Ignored if the program isn't waiting for one. */
    fun input(value: String) {
        if (awaitingInput && !finished) inputs.offer(value)
    }

    /** Ends the run with `done stopped`. Does nothing if it already ended. */
    fun stop() = abort(null, DoneStatus.STOPPED)

    private fun execute(
        content: String,
        version: String,
    ) {
        log.info("Run started")
        try {
            when (val result = runner.run(content, version, Io())) {
                RunResult.Completed -> finish(null, DoneStatus.OK)
                is RunResult.Failed -> finish(RunEvent.Error(result.message, result.diagnostics), DoneStatus.ERROR)
            }
        } catch (e: RunLimitException) {
            finish(RunEvent.Error(e.message!!), DoneStatus.ERROR)
        } catch (_: RunAbortedException) {
            // Ended from outside (stop, deadline): the events are already out
        } catch (e: Exception) {
            if (!finished) log.warn("Run failed", e)
            finish(RunEvent.Error(e.message ?: "The run failed"), DoneStatus.ERROR)
        }
    }

    private inner class Io : ProgramIo {
        override fun println(value: String) {
            outputs++
            outputBytes += value.toByteArray(Charsets.UTF_8).size
            if (outputs > limits.maxOutputs || outputBytes > limits.maxOutputSize.toBytes()) {
                throw RunLimitException("The program printed more than the limit (${limits.maxOutputs} lines or ${limits.maxOutputSize})")
            }
            emit(RunEvent.Output(value))
        }

        override fun readInput(prompt: String): String {
            awaitingInput = true
            try {
                emit(RunEvent.InputRequest(prompt))
                return inputs.poll(limits.inputTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    ?: throw RunLimitException("No input arrived within ${limits.inputTimeout.seconds} s")
            } catch (_: InterruptedException) {
                throw RunAbortedException("interrupted")
            } finally {
                awaitingInput = false
            }
        }
    }

    /** Sends an event while the run is alive; once it ended the program is told to stop. */
    private fun emit(event: RunEvent) {
        synchronized(lock) {
            if (finished) throw RunAbortedException("the run already ended")
            events(event)
        }
    }

    /** Ends the run from outside the program's thread and interrupts it if it's waiting. */
    private fun abort(
        error: RunEvent.Error?,
        status: DoneStatus,
    ) {
        if (finish(error, status)) thread?.interrupt()
    }

    /** Emits the last events once; true if this call is the one that ended the run. */
    private fun finish(
        error: RunEvent.Error?,
        status: DoneStatus,
    ): Boolean {
        synchronized(lock) {
            if (finished) return false
            finished = true
            deadline?.cancel(false)
            log.info("Run ended: {}", status.value)
            error?.let { events(it) }
            events(RunEvent.Done(status))
            return true
        }
    }

    /** A limit of the run was hit inside the program's thread. */
    private class RunLimitException(
        message: String,
    ) : RuntimeException(message)
}
