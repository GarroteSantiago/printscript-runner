package org.snippet.runner

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PrintScriptRunnerApplication

fun main(args: Array<String>) {
    runApplication<PrintScriptRunnerApplication>(*args)
}
