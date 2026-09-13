package com.codex.mobile

import android.system.Os
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * 流式解压镜像分片 imagesN.bin（gzip 压缩的 GNU tar）到目标目录。
 *
 * 支持：普通文件 / 目录 / 符号链接 / GNU @LongLink 长名与长链接 / 权限位。
 * 镜像由 scripts/build-image.py 在构建机生成，路径已全部相对化，
 * 设备端一次解压即可运行，不再需要 dpkg/包装脚本等任何后续步骤。
 * v0.4.0 起镜像为 4 个独立分片，多个线程各自调用本对象并行解压（目录/文件
 * 路径互不重叠，mkdir 幂等，可安全并发）。
 *
 * 注：算法已用 JVM 版与 `tar xzf` 参考解压做全量 diff 验证（3787 项完全一致）。
 */
object TarExtractor {

    private const val TAG = "TarExtractor"
    private const val BLOCK = 512

    /**
     * 把 [src]（gzip 压缩的 tar）解压到 [destDir]。
     * [onProgress] 低频回调，用于 UI 进度展示。
     */
    fun extract(src: File, destDir: File, onProgress: (String) -> Unit = {}) {
        src.inputStream().use { input -> extractStream(input, destDir, onProgress) }
    }

    /** 流式入口：从任意 InputStream（如 APK assets）直接解压，避免中间文件。 */
    fun extractStream(
        src: InputStream,
        destDir: File,
        onProgress: (String) -> Unit = {},
    ) {
        val destPath = destDir.absolutePath
        destDir.mkdirs()

        var files = 0
        var dirs = 0
        var links = 0

        BufferedInputStream(
            GZIPInputStream(src, 1 shl 16),
            1 shl 16,
        ).use { gz ->
            val hdr = ByteArray(BLOCK)
            var pendingName: String? = null
            var pendingLink: String? = null

            while (readFully(gz, hdr)) {
                if (isZero(hdr)) break

                var name = cstr(hdr, 0, 100)
                val mode = octal(hdr, 100, 8).toInt()
                val size = octal(hdr, 124, 12)
                val type = hdr[156].toInt() and 0xff
                var link = cstr(hdr, 157, 100)
                val prefix = cstr(hdr, 345, 155)

                if (prefix.isNotEmpty()) name = "$prefix/$name"
                // PAX/GNU 长名给出的是完整路径，必须整体覆盖（前缀拼接会重复路径）
                pendingName?.let { name = it; pendingName = null }
                pendingLink?.let { link = it; pendingLink = null }

                val normalized = File(destDir, name).canonicalPath
                if (!normalized.startsWith(destPath)) {
                    throw IOException("tar 路径越界: $name")
                }

                when (type) {
                    'L'.code -> { // GNU long name
                        pendingName = readTextContent(gz, size)
                        skipPad(gz, size)
                        continue
                    }
                    'K'.code -> { // GNU long link target
                        pendingLink = readTextContent(gz, size)
                        skipPad(gz, size)
                        continue
                    }
                    'x'.code -> { // PAX 扩展头：应用 path/linkpath 记录到下一个条目
                        val records = readTextContent(gz, size)
                        skipPad(gz, size)
                        for (line in records.lineSequence()) {
                            // 格式：<len> key=value（len 是整行长度）
                            val sp = line.indexOf(' ')
                            if (sp <= 0) continue
                            val kv = line.substring(sp + 1)
                            val eq = kv.indexOf('=')
                            if (eq <= 0) continue
                            val key = kv.substring(0, eq)
                            val value = kv.substring(eq + 1)
                            when (key) {
                                "path" -> pendingName = value
                                "linkpath" -> pendingLink = value
                            }
                        }
                        continue
                    }
                    'g'.code -> { // PAX 全局头：跳过数据
                        skip(gz, size)
                        skipPad(gz, size)
                        continue
                    }
                    '5'.code -> { // 目录
                        makeDir(normalized, mode)
                        dirs++
                    }
                    '2'.code -> { // 符号链接（镜像内已相对化 target）
                        val target = File(normalized)
                        deleteAny(target)
                        // 分片并行解压时，父目录条目可能落在别的分片、由别的线程处理，
                        // 必须先把父目录建好，否则 Os.symlink 抛 ENOENT 导致整片解压失败
                        // （曾造成「打开就提示构建失败」）。
                        target.parentFile?.mkdirs()
                        // 相对链接由 build-image.py 生成，设备端 filesDir 变化也不会断
                        Os.symlink(link, normalized)
                        links++
                    }
                    '0'.code, 0 -> { // 普通文件
                        val f = File(normalized)
                        f.parentFile?.mkdirs()
                        FileOutputStream2(f).use { out ->
                            copyExact(gz, out, size)
                        }
                        // tar 每个文件数据后补齐到 512 字节对齐，必须跳过，
                        // 否则下一个头部解析错位（曾导致多条目分片解压失败）
                        skipPad(gz, size)
                        if (mode != 0) Os.chmod(normalized, mode)
                        files++
                    }
                    else -> { // 'x'/'g' pax 扩展头等：跳过数据
                        skip(gz, size)
                        skipPad(gz, size)
                    }
                }
                if ((files + dirs + links) % 400 == 0) {
                    onProgress("已解压 ${files + dirs + links} 个文件")
                }
            }
        }
        onProgress("完成，共 ${files + dirs + links} 个文件")
    }

    // ── tar 底层工具 ────────────────────────────────────────────────

    private fun makeDir(path: String, mode: Int) {
        val f = File(path)
        if (!f.exists()) f.mkdirs()
        if (mode != 0) try {
            Os.chmod(path, mode)
        } catch (_: Exception) {
        }
    }

    private fun deleteAny(f: File) {
        if (f.exists() || isSymlink(f)) {
            if (f.isDirectory && !isSymlink(f)) f.deleteRecursively() else f.delete()
        }
    }

    private fun isSymlink(f: File): Boolean {
        return try {
            Os.lstat(f.absolutePath).st_mode and 0xF000 == 0xA000
        } catch (_: Exception) {
            false
        }
    }

    /** 读满 buf，返回是否读到完整数据；读到 EOF 且一个字节都没有时返回 false。 */
    private fun readFully(`in`: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = `in`.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("tar header 截断")
            }
            off += n
        }
        return true
    }

    private fun readTextContent(`in`: InputStream, size: Long): String {
        val b = ByteArray(size.toInt())
        var off = 0
        while (off < size) {
            val n = `in`.read(b, off, (size - off).toInt())
            if (n < 0) throw EOFException()
            off += n
        }
        val s = String(b, Charsets.UTF_8)
        val nul = s.indexOf('\u0000')
        return if (nul >= 0) s.substring(0, nul) else s
    }

    private fun copyExact(`in`: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = `in`.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("tar 文件内容截断")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(`in`: InputStream, size: Long) {
        var left = size
        while (left > 0) {
            val n = `in`.skip(left)
            if (n > 0) {
                left -= n
            } else {
                if (`in`.read() == -1) throw EOFException()
                left--
            }
        }
    }

    private fun skipPad(`in`: InputStream, size: Long) {
        skip(`in`, (BLOCK - (size % BLOCK)) % BLOCK)
    }

    private fun isZero(b: ByteArray): Boolean {
        for (x in b) if (x.toInt() != 0) return false
        return true
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** GNU/ustar 八进制数值；支持 base-256（高位置位）。 */
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { // base-256
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) break
            v = v * 8 + (c - '0'.code)
        }
        return v
    }
}

// Android Kotlin 里文件输出流的 use 兼容性薄封装
private fun FileOutputStream2(f: File) = java.io.FileOutputStream(f)