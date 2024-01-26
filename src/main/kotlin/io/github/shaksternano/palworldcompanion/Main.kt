package io.github.shaksternano.palworldcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kronos.rkon.core.Rcon
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

val logger: Logger = LoggerFactory.getLogger("Palworld")

suspend fun main() {
    if (!isRootUser()) {
        logger.error("This program must be run as root")
        exitProcess(1)
    }

    val configFile = Path("config.json")
    val configString = withContext(Dispatchers.IO) {
        if (configFile.exists()) {
            configFile.readLines().joinToString("")
        } else {
            logger.error("No config file found")
            exitProcess(1)
        }
    }
    val config = runCatching {
        Json.decodeFromString<Config>(configString)
    }.getOrElse {
        logger.error("Invalid config file")
        exitProcess(1)
    }
    logger.info("Starting Palworld Server Companion")
    logger.info("Connecting to server...")
    lateinit var rcon: Rcon
    var connected = false
    while (!connected) {
        runCatching {
            rcon = createRcon(config.serverHost, config.rconPort, config.rconPassword)
            connected = true
        }.getOrElse {
            logger.error("Failed to connect to server, trying again in 10 seconds", it)
            delay(10.seconds)
        }
    }
    logger.info("Connected to server")
    while (true) {
        val memoryUsagePercentage = getMemoryUsagePercentage()
        var restarted = false
        if (memoryUsagePercentage > config.maxMemoryPercentage) {
            restarted = true
            logger.info("Memory usage is $memoryUsagePercentage%")
            runCatching {
                restartServer(config, rcon)
            }.getOrElse {
                logger.error("Failed to restart server", it)
            }
        }
        delay(config.checkIntervalMinutes.minutes)
        if (restarted) {
            runCatching {
                rcon.connectSuspend(config.serverHost, config.rconPort, config.rconPassword)
            }.getOrElse {
                logger.error("Failed to reconnect to server", it)
            }
        }
    }
}

suspend fun isRootUser(): Boolean {
    val userId = runCommand("id", "-u").trim()
    return userId == "0"
}

suspend fun getMemoryUsagePercentage(): Double {
    val serverPid = runCommand("pidof", "PalServer-Linux-Test")
    val memoryUsageMessage = runCommand("ps", "-p", serverPid, "-o", "%mem")
    return memoryUsageMessage.split("\n")[1].toDouble()
}

suspend fun restartServer(config: Config, rcon: Rcon) {
    runCatching {
        restartSequence(
            rcon,
            minutes(
                10,
                5,
                1,
            ) + seconds(
                30,
                10,
                9,
                8,
                7,
                6,
                5,
                4,
                3,
                2,
                1,
            ),
        )
        rcon.broadcast("Restarting server")
        val savedMessage = rcon.commandSuspend("save").trim()
        logger.info(savedMessage)
        withContext(Dispatchers.IO) {
            rcon.disconnect()
        }
    }.getOrElse {
        logger.error("Error while restarting server", it)
    }
    logger.info("Restarting server")
    val whitespaceRegex = "\\s+".toRegex()
    runCommand(config.restartCommand.split(whitespaceRegex).toList())
}

fun minutes(vararg minutes: Int): List<Pair<Duration, DurationUnit>> =
    minutes.map { it.minutes to DurationUnit.MINUTES }

fun seconds(vararg seconds: Int): List<Pair<Duration, DurationUnit>> =
    seconds.map { it.seconds to DurationUnit.SECONDS }


suspend fun restartSequence(rcon: Rcon, warningIntervals: List<Pair<Duration, DurationUnit>>) {
    warningIntervals.windowed(size = 2, step = 1, partialWindows = true).forEach {
        val (duration, timeUnit) = it[0]
        when (timeUnit) {
            DurationUnit.MINUTES -> {
                val minutes = duration.inWholeMinutes.toInt()
                var message = "Restarting server in $minutes minute"
                if (minutes != 1) {
                    message += "s"
                }
                logger.info(message)
                rcon.broadcastRestartMinutes(minutes)
            }

            DurationUnit.SECONDS -> {
                val seconds = duration.inWholeSeconds.toInt()
                var message = "Restarting server in $seconds second"
                if (seconds != 1) {
                    message += "s"
                }
                logger.info(message)
                rcon.broadcastRestartSeconds(seconds)
            }

            else -> {}
        }
        val nextWarningDuration = if (it.size == 2) {
            val (nextDuration, _) = it[1]
            duration - nextDuration
        } else {
            duration
        }
        delay(nextWarningDuration)
    }
}

suspend fun runCommand(vararg command: String): String =
    runCommand(command.toList())

suspend fun runCommand(command: List<String>): String {
    val process = withContext(Dispatchers.IO) {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
    }

    val reader = InputStreamReader(process.inputStream)
    val output = StringBuilder()

    reader.useLines { lines ->
        lines.forEach {
            output.appendLine(it)
        }
    }

    val exitCode = withContext(Dispatchers.IO) {
        process.waitFor()
    }

    return if (exitCode == 0) {
        output.toString().trim()
    } else {
        "Command failed with exit code $exitCode"
    }
}

@Serializable
data class Config(
    val checkIntervalMinutes: Double = 5.0,
    val maxMemoryPercentage: Double = 50.0,
    val serverHost: String,
    val rconPort: Int,
    val rconPassword: String,
    val restartCommand: String,
)
