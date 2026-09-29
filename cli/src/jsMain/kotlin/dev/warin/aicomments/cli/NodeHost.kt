package dev.warin.aicomments.cli

import org.khronos.webgl.Int8Array

private val childProcess: dynamic = js("require('child_process')")
private val fs: dynamic = js("require('fs')")
private val os: dynamic = js("require('os')")
private val nodePath: dynamic = js("require('path')")
private val nodeBuffer: dynamic = js("Buffer")

/** [Host] for Node, so the VS Code extension strips commits with exactly the code the Claude hook uses. */
object NodeHost : Host {
    override fun git(dir: String, vararg args: String): ProcessResult {
        val options: dynamic = js("({})")
        options.stdio = arrayOf("ignore", "pipe", "ignore")
        options.maxBuffer = 256 * 1024 * 1024
        return try {
            ProcessResult(0, toByteArray(childProcess.execFileSync("git", arrayOf("-C", dir, *args), options)))
        } catch (error: dynamic) {
            val status = error.status as? Int ?: -1
            val stdout = error.stdout
            ProcessResult(status, if (stdout != null && stdout != undefined) toByteArray(stdout) else ByteArray(0))
        }
    }

    override fun readBytes(path: String): ByteArray? = try {
        toByteArray(fs.readFileSync(path))
    } catch (_: dynamic) {
        null
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        fs.writeFileSync(path, toBuffer(bytes))
    }

    override fun writeTemp(bytes: ByteArray): String {
        val dir = fs.mkdtempSync(nodePath.join(os.tmpdir(), "ai-comments-")) as String
        val path = nodePath.join(dir, "blob") as String
        fs.writeFileSync(path, toBuffer(bytes))
        return path
    }

    override fun delete(path: String) {
        val options: dynamic = js("({ recursive: true, force: true })")
        fs.rmSync(nodePath.dirname(path), options)
    }

    override fun expandHome(path: String): String =
        if (path.startsWith("~")) (os.homedir() as String) + path.removePrefix("~") else path

    /** A Kotlin/JS ByteArray is an Int8Array, so a Buffer's bytes can be viewed without copying. */
    private fun toByteArray(buffer: dynamic): ByteArray =
        Int8Array(buffer.buffer, buffer.byteOffset as Int, buffer.length as Int).unsafeCast<ByteArray>()

    private fun toBuffer(bytes: ByteArray): dynamic {
        val view = bytes.unsafeCast<Int8Array>()
        return nodeBuffer.from(view.buffer, view.byteOffset, view.length)
    }
}
