package net.pterodactylus.cd2a

import java.io.File

fun runProcess(command: List<String>, directory: File? = null, stdout: File? = null) {
	(stdout ?: tempFile("stdout-", ".txt"))
			.also { tempFile ->
				ProcessBuilder()
						.let { processBuilder -> directory?.let { processBuilder.directory(it) } ?: processBuilder }
						.command(*command.toTypedArray())
						.redirectErrorStream(true)
						.redirectOutput(tempFile)
						.start().waitFor()
			}.also { if (stdout == null) it.delete() }
}
