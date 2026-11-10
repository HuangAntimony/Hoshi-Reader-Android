package moe.antimony.hoshi.epub

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Older Drive downloads retained the cache group when moved into the book library. */
internal fun migrateBookCacheGroups(booksDirectory: File, cacheDirectory: File, completionMarker: File) {
    if (completionMarker.isFile) return

    val uid = Os.getuid()
    val group = Os.getgid()
    val cacheGroup = Os.stat(cacheDirectory.path).st_gid
    if (cacheGroup != group) {
        Files.walkFileTree(booksDirectory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) {
                    try {
                        val path = file.toString()
                        val stat = Os.lstat(path)
                        if (OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == uid && stat.st_gid == cacheGroup) {
                            Os.lchown(path, -1, group)
                        }
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.ENOENT) throw error
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                if (error is NoSuchFileException) return FileVisitResult.CONTINUE
                throw error
            }
        })
    }
    completionMarker.createNewFile()
}
