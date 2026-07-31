package com.codex.mobile

import android.content.Context
import java.io.File

/**
 * v0.3.0 起运行环境由构建机预组装成成品镜像，v0.4.0 起为 4 个分片
 * （assets/images0..3.bin），设备端用 [TarExtractor] 并行解压即用。
 * 本对象只负责提供路径常量与文件删除工具。
 */
object BootstrapInstaller {

    data class Paths(
        val filesDir: String,
        val prefixDir: String,
        val homeDir: String,
        val tmpDir: String,
    )

    fun getPaths(context: Context): Paths {
        val filesDir = context.filesDir.absolutePath
        return Paths(
            filesDir = filesDir,
            prefixDir = "$filesDir/usr",
            homeDir = "$filesDir/home",
            tmpDir = "$filesDir/usr/tmp",
        )
    }

    fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        fileOrDir.delete()
    }
}