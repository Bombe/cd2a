package net.pterodactylus.cd2a

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

fun tempFile(prefix: String = "", suffix: String = "", directory: String = baseDirectory) =
		File.createTempFile(prefix, suffix, File(directory))!!

fun <R> File.use(block: (File) -> R): R =
		block(this)
				.also { Files.walk(this.toPath()).map(Path::toFile).sorted(reverseOrder()).forEach { it.delete() } }
				.also { delete() }
				.also { deleteOnExit() }
