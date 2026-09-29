@file:OptIn(ExperimentalForeignApi::class)

package dev.warin.aicomments.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.close
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkstemp
import platform.posix.pclose
import platform.posix.popen
import platform.posix.stderr
import platform.posix.stdin
import platform.posix.unlink
import platform.posix.write

object PosixHost : Host {
    override fun git(dir: String, vararg args: String): ProcessResult {
        val command = (listOf("git", "-C", dir) + args).joinToString(" ") { shellQuote(it) } + " 2>/dev/null"
        val pipe = popen(command, "r") ?: return ProcessResult(-1, ByteArray(0))
        val output = readAll { buffer, size -> fread(buffer, 1u, size.toULong(), pipe).toInt() }
        val status = pclose(pipe)
        return ProcessResult((status shr 8) and 0xff, output)
    }

    override fun readBytes(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            return readAll { buffer, size -> fread(buffer, 1u, size.toULong(), file).toInt() }
        } finally {
            fclose(file)
        }
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("Cannot write $path")
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) }
        } finally {
            fclose(file)
        }
    }

    override fun writeTemp(bytes: ByteArray): String = memScoped {
        val template = "${getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"}/ai-comments-XXXXXX"
        val path = allocArray<ByteVar>(template.length + 1)
        template.encodeToByteArray().forEachIndexed { i, byte -> path[i] = byte }
        path[template.length] = 0
        val fd = mkstemp(path)
        if (fd < 0) error("Cannot create a temporary file")
        if (bytes.isNotEmpty()) bytes.usePinned { write(fd, it.addressOf(0), bytes.size.toULong()) }
        close(fd)
        path.toKString()
    }

    override fun delete(path: String) {
        unlink(path)
    }

    override fun expandHome(path: String): String {
        if (!path.startsWith("~")) return path
        val home = getenv("HOME")?.toKString() ?: return path
        return home + path.removePrefix("~")
    }

    fun printError(message: String) {
        fputs("$message\n", stderr)
    }

    fun readStdin(): String = readAll { buffer, size -> fread(buffer, 1u, size.toULong(), stdin).toInt() }.decodeToString()

    private inline fun readAll(read: (kotlinx.cinterop.CPointer<ByteVar>, Int) -> Int): ByteArray = memScoped {
        val size = 64 * 1024
        val buffer = allocArray<ByteVar>(size)
        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val count = read(buffer, size)
            if (count <= 0) break
            chunks += buffer.readBytes(count)
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        result
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
