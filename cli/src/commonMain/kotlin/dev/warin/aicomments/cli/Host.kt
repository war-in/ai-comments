package dev.warin.aicomments.cli

class ProcessResult(val exitCode: Int, val stdout: ByteArray) {
    val text get() = stdout.decodeToString()
}

/** Everything the CLI needs from the operating system, so the logic stays in common code. */
interface Host {
    fun git(dir: String, vararg args: String): ProcessResult

    fun readBytes(path: String): ByteArray?

    fun writeBytes(path: String, bytes: ByteArray)

    /** Writes [bytes] to a new temporary file and returns its path. */
    fun writeTemp(bytes: ByteArray): String

    fun delete(path: String)

    fun expandHome(path: String): String
}
