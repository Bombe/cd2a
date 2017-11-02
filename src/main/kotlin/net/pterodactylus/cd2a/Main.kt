package net.pterodactylus.cd2a

import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.httpGet
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.nio.file.Paths
import java.util.Arrays
import java.util.zip.ZipInputStream

val baseDirectory = "/Users/bombe/Temp/dp"

fun main(args: Array<String>) {
	getDemoparties()
			.filter { it.name == "Revision 2017" && it.year == 2017 }
			.forEach { processDemoparty(it) }
}

fun processDemoparty(party: Demoparty) {
	indent {
		println("Processing ${party.name}...")
		party
				.let(Demoparty::loadCompos)
				.compos
				.forEach { advance { processCompo(it, this) } }
	}
}

fun processCompo(compo: Compo, indent: Indent = Indent()) {
	with(indent) {
		println("Processing ${compo.name}...")
		indent.advance {
			println("Entries: ${compo.entries.size}")
			val entries = compo.entries.filterNot { "/graphics/" in it.url }
			println("Eligible Entries: ${entries.size}")
			entries.forEach { processEntry(it, this) }
		}
	}
}

fun processEntry(entry: Entry, indent: Indent = Indent()) {
	with(indent) {
		println("Entry: ${entry.artist} - ${entry.name} (${entry.url})")
		val downloadLinks = entry.downloadLinks()
		indent.advance {
			println("Download Links: ${downloadLinks.size}")
			val content = entry.download(downloadLinks) ?: return@advance
			val relevantFiles = content.getRelevantFiles()
					.toList()
					.let {
						when {
							it.size > 1 -> it.filterNot { it.name.isUrl() }
							else -> it
						}
					}
			println("Relevant Files: ${relevantFiles.size}")
			if (relevantFiles.isEmpty()) return@advance
			println("Storing Files...")
			relevantFiles.forEach { it.store(entry, this) }
		}
	}
}

fun Content.store(entry: Entry, indent: Indent) =
		with(indent) {
			entry.directory()
					.toFile()
					.apply {
						mkdirs()
					}
					.let {
						generateSequence(2 to File(it, entry.base().toString() + "." + name.split(".").last())) { last ->
							if (!last.second.exists()) {
								null
							} else {
								(last.first + 1) to File(it, entry.base().toString() + " (${last.first})." + name.split(".").last())
							}
						}.last().second
					}
					.also { println("Storing ${content.size} Bytes as ${it.path}.") }
					.writeBytes(content)
		}

fun Content.getRelevantFiles(): Sequence<Content> =
		when {
			name.toLowerCase().endsWith(".zip") -> ZipInputStream(content.inputStream()).use { zipInputStream ->
				generateSequence {
					tryOrNull {
						zipInputStream.nextEntry?.let { zipEntry ->
							Content(entry, zipEntry.name.split("/").last(), zipInputStream.readBytes())
						}
					}
				}
						.flatMap { it.getRelevantFiles() }
			}
			name.isMusic() -> sequenceOf(this)
			name.isModule() -> sequenceOf(this)
			name.isSid() -> sequenceOf(this)
			name.isUrl() -> sequenceOf(this)
			else -> emptySequence()
		}

fun String.isUrl() = toLowerCase().endsWith(".url")

fun String.isSid() = toLowerCase()
		.split(".").last() in listOf("sid", "psid", "prg", "d64")

fun String.isFlac() = toLowerCase().endsWith(".flac")

fun String.isMusic() = toLowerCase()
		.split(".").last() in listOf("mp3", "ogg", "flac", "opus", "aac", "m4a", "wav")

fun String.isModule() = hasModuleSuffix() || hasModulePrefix()

fun String.hasModuleSuffix() = toLowerCase()
		.split(".").last() in listOf("xm", "mod", "digi", "dbm")

fun String.hasModulePrefix() = toLowerCase()
		.split("/").last()
		.split(".").first() in listOf("xm", "mod")

fun Entry.download(links: List<String>) =
		links.fold(null as Content?) { previous, link ->
			previous ?: tryOrNull {
				when {
					"youtube.com" in link -> Content(this, name + ".url", link.toByteArray())
					else -> link.httpGet().response()
							.takeIf { it.third.component2() == null }
							?.third?.component1()
							?.let { Content(this, link.split("/").last().decode(), it) }
				}
			}
		}

fun String.decode() = URLDecoder.decode(this, "UTF-8")!!

fun <R> tryOrNull(silent: Boolean = true, block: () -> R): R? = try {
	block()
} catch (t: Throwable) {
	if (!silent) t.printStackTrace()
	null
}

data class Content(val entry: Entry, val name: String, val content: ByteArray) {
	override fun hashCode() = entry.hashCode() xor name.hashCode() xor Arrays.hashCode(content)

	override fun equals(other: Any?) =
			(other as Content?)
					?.let { other?.entry == entry && other?.name == name && Arrays.equals(other?.content, content) }
					?: false

	override fun toString() = "${javaClass.simpleName}(name=$name, content=ByteArray(${content.size}))"
}

fun Entry.downloadLinks() =
		url.httpGet().toDocument()
				?.let { document ->
					listOf(
							*document.select("li[class='download_link sceneorg'] div[class=secondary] a")
									.map { it.attr("href") }
									.filter { it.startsWith("http") }
									.toTypedArray(),
							document.select("a[class=youtube]").firstOrNull()?.attr("href")
					).filterNotNull()
				}
				?: emptyList<String>()

fun Entry.base() = Paths.get("${compo.party.name} - ${compo.name} - ${index.track} - $artist - $name")!!

fun Entry.directory() = Paths.get(baseDirectory,
		compo.party.year.toString(),
		compo.party.name,
		compo.name)!!

fun Demoparty.loadCompos() =
		url.httpGet().toDocument()
				?.let {
					it.select("section[class*=competition]")
							.map { section ->
								Compo(this@loadCompos, section.select("h4").text().clean())
										.let { compo ->
											compo.copy(entries = section.select("tr[class=result]")
													.map {
														Entry(
																compo,
																it.select("div[class=result__title] a").first().absUrl("href"),
																it.select("[class=result__ranking]").text().toInt(),
																it.select("div[class=result__title] a").text(),
																it.select("div[class=result__author] a").eachText().joinToString(" & ")
														)
													})
										}
							}
				}
				?.let { copy(compos = it) }
				?: this

fun String.clean() = replace(Regex("( / |/)"), " & ")

fun getDemoparties(): Collection<Demoparty> =
		"https://demozoo.org/parties/by_date/"
				.httpGet()
				.toDocument()
				?.select("h3, ul li a")
				?.fold(0 to emptyList<Demoparty>()) { (year, parties), element ->
					if (element.tagName() == "h3") {
						element.text().toInt() to parties
					} else {
						year to (parties + Demoparty(element.absUrl("href"), element.text().titleCase(), year))
					}
				}?.second
				?: emptyList()

fun String.titleCase() = split(" ")
		.joinToString(" ") { it.first().toUpperCase() + it.drop(1).toLowerCase() }

fun Request.toDocument() =
		responseString()
				.takeIf { it.third.component2() == null }
				?.third?.component1()
				?.let { Jsoup.parse(it, url.toString()) }

val Int.track get() = "%02d".format(this)

data class Entry(val compo: Compo, val url: String, val index: Int, val name: String, val artist: String)
data class Compo(val party: Demoparty, val name: String, val entries: List<Entry> = emptyList())
data class Demoparty(val url: String, val name: String, val year: Int, val compos: Collection<Compo> = emptyList())

class Indent(private val indent: Int = 0) {
	private operator fun String.times(count: Int) = 0.until(count).joinToString("") { this }
	fun println(text: String) = System.out.println("  " * indent + text)
	fun advance(block: Indent.() -> Unit) = block(Indent(indent + 1))
	fun apply(block: Indent.() -> Unit) = block(this)
}

fun indent(block: Indent.() -> Unit) = Indent(-1).advance(block)
