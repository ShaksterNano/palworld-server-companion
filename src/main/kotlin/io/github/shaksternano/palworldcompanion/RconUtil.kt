package io.github.shaksternano.palworldcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kronos.rkon.core.Rcon

suspend fun createRcon(host: String, port: Int, password: String): Rcon = withContext(Dispatchers.IO) {
    RetryingRcon(host, port, password.toByteArray())
}

suspend fun Rcon.connectSuspend(host: String, port: Int, password: String) = withContext(Dispatchers.IO) {
    connect(host, port, password.toByteArray())
}

suspend fun Rcon.commandSuspend(payload: String): String = withContext(Dispatchers.IO) {
    command(payload)
}

suspend fun Rcon.broadcast(message: String) =
    commandSuspend("broadcast ${message.replace(' ', '_')}")

suspend fun Rcon.broadcastRestartMinutes(minutes: Int) {
    var message = "Restarting server in $minutes minute"
    if (minutes != 1) {
        message += "s"
    }
    broadcast(message)
}

suspend fun Rcon.broadcastRestartSeconds(seconds: Int) {
    var message = "Restarting server in $seconds second"
    if (seconds != 1) {
        message += "s"
    }
    broadcast(message)
}

private class RetryingRcon(
    private val host: String,
    private val port: Int,
    private val password: ByteArray,
) : Rcon(host, port, password) {

    override fun command(payload: String): String =
        runCatching {
            super.command(payload)
        }.getOrElse { t ->
            logger.error("Error while executing command: $payload", t)
            runCatching {
                disconnect()
            }.getOrElse {
                logger.error("Error while disconnecting", it)
            }
            connect(host, port, password)
            super.command(payload)
        }
}
