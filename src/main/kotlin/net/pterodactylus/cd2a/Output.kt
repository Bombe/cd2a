package net.pterodactylus.cd2a

class Output(private val indent: Int = 0) {

	fun <R> indent(action: Output.() -> R): R =
			action(Output(indent + 1))

	fun println(line: String) {
		kotlin.io.println("  ".repeat(indent) + line)
	}

}

fun startOutput(action: Output.() -> Unit) = action(Output())
