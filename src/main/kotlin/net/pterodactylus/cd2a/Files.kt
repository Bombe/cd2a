package net.pterodactylus.cd2a

import java.io.File

fun tempFile(prefix: String = "", suffix: String = "", directory: String = baseDirectory) =
		File.createTempFile(prefix, suffix, File(directory))!!
