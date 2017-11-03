package net.pterodactylus.cd2a

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.httpGet
import org.jsoup.Jsoup
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import java.util.stream.Stream
import java.util.zip.ZipInputStream

val baseDirectory = "/Users/bombe/Temp/dp"

fun main(args: Array<String>) {
	getDemoparties()
			.filter { it.name == "Revision 2017" && it.year == 2017 }
			.forEachProgress { index, total, party ->
				println("Processing ${party.name} ($index/$total)...")
				processDemoparty(party)
			}
}

fun processDemoparty(party: Demoparty) {
	indent {
		party
				.let(Demoparty::loadCompos)
				.compos
				.forEachProgress { index, total, compo ->
					advance {
						println("Processing ${compo.name} ($index/$total)...")
						processCompo(compo, this)
					}
				}
	}
}

fun <T> Collection<T>.forEachProgress(block: (Int, Int, T) -> Unit) =
		forEachIndexed { index, element -> block(index, size, element) }

fun processCompo(compo: Compo, indent: Indent = Indent()) {
	indent.advance {
		println("Entries: ${compo.entries.size}")
		val entries = compo.entries.filterNot { "/graphics/" in it.url }
		println("Eligible Entries: ${entries.size}")
		entries.forEach { processEntry(it, this) }
	}
}

fun processEntry(entry: Entry, indent: Indent = Indent()) {
	with(indent) {
		println("Entry: ${entry.artist} - ${entry.name} (${entry.url})")
		if (entry.directory().toFile().list { _, name -> name.startsWith(entry.base().toString()) }?.isEmpty() == false) {
			indent.advance { println("Skipping.") }
			return
		}
		val downloadLinks = entry.downloadLinks()
		indent.advance {
			println("Download Links: ${downloadLinks.size}")
			if (downloadLinks.isEmpty()) return@advance
			val content = entry.download(downloadLinks.filterNot { it.isYoutubeLink() })
			val relevantFiles = content?.getRelevantFiles()?.toList() ?: emptyList()
			println("Relevant Files: ${relevantFiles.size}")
			if (relevantFiles.isEmpty()) {
				println("Didn't get anything from ${content?.name}.")
				val youtubeLink = downloadLinks.firstOrNull { it.isYoutubeLink() } ?: return@advance
				println("Downloading from YouTube...")
				entry.downloadYoutubeLink(youtubeLink)
			} else {
				println("Storing Files...")
				relevantFiles.forEach { it.store(entry, this) }
			}
		}
	}
}

const val youtubeDlLocation = "/usr/local/bin/youtube-dl"
fun Entry.downloadYoutubeLink(link: String) {
	val stdout = tempFile("stdout-$link-", ".url").apply { deleteOnExit() }
	directory().toFile().mkdirs()
	ProcessBuilder()
			.directory(directory().toFile())
			.command(youtubeDlLocation, "-x", "-o", base().toString() + ".out", link)
			.redirectErrorStream(true)
			.redirectOutput(stdout)
			.start().waitFor()
	stdout.delete()
}

fun String.isYoutubeLink() = startsWith("http://www.youtube.com/") || startsWith("https://www.youtube.com/")

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
					.let {
						Files.move(file.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
					}
					.let { Unit }
		}

fun Content.getRelevantFiles(): List<Content> =
		when {
			name.toLowerCase().endsWith(".zip") -> ZipInputStream(file.inputStream()).use { zipInputStream ->
				generateSequence {
					tryOrNull(true) {
						zipInputStream.nextEntry?.let { zipEntry ->
							tempFile("$name-", "-${zipEntry.name.split("/").last()}")
									.apply { outputStream().use { zipInputStream.copyTo(it) } }
									.let { Content(entry, zipEntry.name.replace("/", "-"), it) }
						}
					}
				}
						.toList()
						.also { this@getRelevantFiles.remove() }
						.flatMap { it.getRelevantFiles() }
			}
			name.toLowerCase().endsWith(".lha") -> unpackLharc()
			name.toLowerCase().endsWith(".7z") -> unpack7Zip()
			name.toLowerCase().endsWith(".rar") -> unpackRar()
			name.toLowerCase().endsWith(".tar") -> unpackTar()
			listOf(".tar.gz", ".tgz").any { name.endsWith(it) } -> unpackTarGz()
			name.isMusic() -> listOf(this)
			name.isModule() -> listOf(this)
			name.isSid() -> listOf(this)
			name.isUrl() -> listOf(this)
			name.isVideo() -> listOf(this)
			else -> emptyList<Content>().also { this@getRelevantFiles.remove() }
		}

val tarLocation = "/usr/bin/tar"
fun Content.unpackTar() =
		unpack("tar") { listOf(tarLocation, "-x", "-f", file.toString()) }

fun Content.unpackTarGz() =
		unpack("targz") { listOf(tarLocation, "-x", "-z", "-f", file.toString()) }

val lharcLocation = "/usr/local/bin/lha"
fun Content.unpackLharc() =
		unpack("lha") { listOf(lharcLocation, "x", file.absolutePath) }

val sevenZipLocation = "/usr/local/bin/7z"
fun Content.unpack7Zip() =
		unpack("7zip") { listOf(sevenZipLocation, "x", file.absolutePath) }

val unrarLocation = "/usr/local/bin/unrar"
fun Content.unpackRar() =
		unpack("rar") { listOf(unrarLocation, "x", file.absolutePath) }

fun Content.unpack(algorithm: String, processBuilder: () -> List<String>) =
		tempFile("$algorithm-$name-", ".out")
				.apply {
					delete()
					mkdir()
				}
				.use { directory ->
					tempFile("stdout-$name-", ".txt").use { stdout ->
						ProcessBuilder()
								.directory(directory)
								.command(*processBuilder().toTypedArray())
								.redirectErrorStream(true)
								.redirectOutput(stdout)
								.start().waitFor()
					}
					Files.walk(directory.toPath()).toList().mapNotNull { path ->
						if (path.toFile().isDirectory) return@mapNotNull null
						val destination = tempFile("file-", "-${path.fileName.toString().split("/").last()}")
						Files.move(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
						Content(entry, path.fileName.toString().replace("/", "-"), destination)
					}
							.also { this@unpack.remove() }
							.flatMap(Content::getRelevantFiles)
				}

fun <T> Stream<T>.toList(): List<T> = collect(Collectors.toList())

fun <R> File.use(block: (File) -> R): R =
		block(this)
				.also { Files.walk(this.toPath()).toList().forEach { it.toFile().delete() } }
				.also { delete() }
				.also { deleteOnExit() }

fun Content.remove() {
	file.delete()
}

fun String.isVideo() = toLowerCase()
		.split(".").last() in listOf("mp4", "m4v", "mpg", "mkv", "avi", "mov", "wmv", "flv")

fun String.isUrl() = toLowerCase().endsWith(".url")

fun String.isSid() = toLowerCase()
		.split(".").last() in listOf("sid", "psid", "prg", "d64")

fun String.isMusic() = toLowerCase()
		.split(".").last() in listOf("mp3", "ogg", "flac", "opus", "aac", "m4a", "wav")

fun String.isModule() = hasModuleSuffix() || hasModulePrefix()

fun String.hasModuleSuffix() = toLowerCase()
		.split(".").last() in listOf("xm", "mod", "digi", "dbm", "it", "s3m", "oct", "med", "ahx", "thx")

fun String.hasModulePrefix() = toLowerCase()
		.split("/").last()
		.split(".").first() in listOf("xm", "mod", "thx")

fun Entry.download(links: List<String>) =
		links.fold(null as Content?) { previous, link ->
			previous ?: tryOrNull {
				tempFile("$name-", "-${link.split("/").last()}")
						.let { tempFile ->
							Fuel.download(link).destination { _, _ -> tempFile }
									.response()
									.takeIf { it.third.component2() == null }
									?.let { Content(this, link.split("/").last().decode(), tempFile) }
						}
			}
		}

fun tempFile(prefix: String = "", suffix: String = "", directory: String = baseDirectory) =
		File.createTempFile(prefix, suffix, File(directory))!!

fun String.decode() = URLDecoder.decode(this, "UTF-8")!!

fun <R> tryOrNull(silent: Boolean = true, block: () -> R): R? = try {
	block()
} catch (t: Throwable) {
	if (!silent) t.printStackTrace()
	null
}

data class Content(val entry: Entry, val name: String, val file: File)

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
								Compo(this@loadCompos, section.select("h4").text().cleanCompo())
										.let { compo ->
											compo.copy(entries = section.select("tr[class=result]")
													.map {
														Entry(
																compo,
																it.select("div[class=result__title] a").first().absUrl("href"),
																it.select("[class=result__ranking]").text().toIntOrNull() ?: 0,
																it.select("div[class=result__title] a").text().cleanTitle(),
																it.select("div[class=result__author]").text().cleanAuthor()
														)
													})
										}
							}
				}
				?.let { copy(compos = it) }
				?: this

fun String.cleanCompo() = replace(Regex("( / |/)"), " & ")
fun String.cleanTitle() = replace("/", "_")
fun String.cleanAuthor() = cleanTitle()

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
}

fun indent(block: Indent.() -> Unit) = Indent(-1).advance(block)
