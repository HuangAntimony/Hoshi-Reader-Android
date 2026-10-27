package moe.antimony.hoshi.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun writeMinimalExtractedEpub(
    root: File,
    title: String = "Sample Book",
    language: String? = null,
    author: String? = null,
    coverId: String = "cover",
    coverProperties: String? = "cover-image",
    firstChapterHtml: String = "<html><body><p>First</p></body></html>",
    secondChapterHtml: String = "<html><body><p>Second</p></body></html>",
) {
    root.resolve("META-INF").mkdirs()
    root.resolve("META-INF/container.xml").writeText(
        """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent(),
    )

    root.resolve("OPS/text").mkdirs()
    root.resolve("OPS/styles").mkdirs()
    root.resolve("OPS/images").mkdirs()
    root.resolve("OPS/text/chapter-1.xhtml").writeText(firstChapterHtml)
    root.resolve("OPS/text/chapter-2.xhtml").writeText(secondChapterHtml)
    root.resolve("OPS/styles/book.css").writeText("body {}")
    writeTestEpubCover(root.resolve("OPS/images/cover.jpg"))
    val languageElement = language?.let { "<dc:language>$it</dc:language>" }.orEmpty()
    val authorElement = author?.let { "<dc:creator>$it</dc:creator>" }.orEmpty()
    val coverPropertiesAttribute = coverProperties?.let { " properties=\"$it\"" }.orEmpty()
    root.resolve("OPS/package.opf").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>$title</dc:title>
              $languageElement
              $authorElement
          </metadata>
          <manifest>
            <item id="chapter-1" href="text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
            <item id="chapter-2" href="text/chapter-2.xhtml" media-type="application/xhtml+xml"/>
            <item id="style" href="styles/book.css" media-type="text/css"/>
            <item id="$coverId" href="images/cover.jpg" media-type="image/jpeg"$coverPropertiesAttribute/>
          </manifest>
          <spine>
            <itemref idref="chapter-1"/>
            <itemref idref="chapter-2"/>
          </spine>
        </package>
        """.trimIndent(),
    )
}

internal fun writeMinimalEpubArchive(archive: File, title: String = "Sample Book") {
    val tempRoot = requireNotNull(archive.parentFile).resolve("${archive.nameWithoutExtension}-extracted")
    tempRoot.deleteRecursively()
    writeMinimalExtractedEpub(tempRoot, title = title)
    ZipOutputStream(archive.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("mimetype"))
        zip.write("application/epub+zip".toByteArray())
        zip.closeEntry()
        tempRoot.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(tempRoot).invariantSeparatorsPath }
            .forEach { file ->
                val entryName = file.relativeTo(tempRoot).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
    }
    tempRoot.deleteRecursively()
}

internal fun writeTestEpubCover(file: File) {
    file.writeBytes(testEpubCoverBytes())
}

internal fun testEpubCoverBytes(): ByteArray = java.io.ByteArrayOutputStream().also { output ->
    javax.imageio.ImageIO.write(java.awt.image.BufferedImage(10, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", output)
}.toByteArray()
