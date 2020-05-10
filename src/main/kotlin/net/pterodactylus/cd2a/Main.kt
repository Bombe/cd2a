package net.pterodactylus.cd2a

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.kittinunf.fuel.httpGet
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import java.util.stream.Stream

const val baseDirectory = "/Users/bombe/Temp/dp"

fun main(args: Array<String>) {
	val entryProcessor: (Entry, Output) -> Unit = if ("--print" in args) { entry: Entry, output: Output -> output.println(entry.base().toString()) } else ::processEntry
	getDemoparties()
//			.filter { it.name == "Revision 2017" }
//			.filter { it.year == 2017 }
			.forEachProgress { index, total, party ->
				startOutput {
					println("Processing ${party.name} ($index/$total)...")
					processDemoparty(party, this, entryProcessor)
				}
			}
}

fun processDemoparty(party: Demoparty, output: Output, entryProcessor: (Entry, Output) -> Unit = ::processEntry) {
	output.indent {
		party
				.compos
				.forEachProgress { index, total, compo ->
					println("Processing ${compo.name} ($index/$total)...")
					processCompo(compo, this, entryProcessor)
				}
	}
}

fun <T> Collection<T>.forEachProgress(block: (Int, Int, T) -> Unit) =
		forEachIndexed { index, element -> block(index, size, element) }

fun processCompo(compo: Compo, output: Output, entryProcessor: (Entry, Output) -> Unit = ::processEntry) {
	output.indent {
		println("Entries: ${compo.entries.size}")
		val entries = compo.entries.filterNot { it.type == "graphics" }
		println("Eligible Entries: ${entries.size}")
		entries.forEach { entryProcessor(it, this) }
	}
}

fun processEntry(entry: Entry, output: Output) {
	output.indent {
		println("Entry: ${entry.artist} - ${entry.name} (${entry.id})")
		if (entry.directory().toFile().list { _, name -> name.startsWith(entry.base().toString()) }?.isEmpty() == false) {
			output.indent { println("Skipping.") }
			return@indent
		}
		val downloadLinks = entry.downloadLinks()
		indent {
			println("Download Links: ${downloadLinks.size}")
			if (downloadLinks.isEmpty()) return@indent
			val content = entry.download(downloadLinks.filterNot { it.isYoutubeLink() }, this)
			val relevantFiles = content.flatMap { it.getRelevantFiles(output) }
			println("Relevant Files: ${relevantFiles.size} ($relevantFiles)")
			if (relevantFiles.isEmpty()) {
				println("Didn't get anything from ${content.joinToString { it.name }}.")
				val youtubeLink = downloadLinks.firstOrNull { it.isYoutubeLink() } ?: return@indent
				println("Downloading from YouTube...")
				entry.downloadYoutubeLink(youtubeLink)
			} else {
				println("Storing Files...")
				relevantFiles.store(entry)
			}
		}
	}
}

private val youtubeDlLocation = "/usr/local/bin/youtube-dl"
fun Entry.downloadYoutubeLink(link: String) {
	directory().toFile().mkdirs()
	runProcess(listOf(youtubeDlLocation, "-x", "-o", base().toString() + ".out", link), directory().toFile())
}

fun String.isYoutubeLink() = startsWith("http://www.youtube.com/") || startsWith("https://www.youtube.com/")

private fun generateFilename(entry: Entry, suffix: String, nameWithoutSuffix: String? = null, number: Int? = null) =
		listOfNotNull(
				entry.base(),
				nameWithoutSuffix?.let { " - $it" },
				number?.let { " ($it)" },
				".$suffix"
		).joinToString("")

fun List<Content>.store(entry: Entry) =
		forEach { content ->
			fun generateName(number: Int? = null) =
					content.name.split(".").let { nameParts ->
						generateFilename(
								entry,
								nameParts.last(),
								this@store.takeIf { size > 1 }?.let { nameParts.dropLast(1).joinToString(".") },
								number)
					}
			entry.directory()
					.toFile()
					.apply { mkdirs() }
					.let {
						generateSequence(2 to File(it, generateName(null))) { (nextNumber, file) ->
							((nextNumber + 1) to File(it, generateName(nextNumber)))
									.takeIf { file.exists() }
						}.last().second
					}
					.let {
						Files.move(content.file.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
					}
		}

fun Content.getRelevantFiles(output: Output): List<Content> =
		when {
			name.toLowerCase().endsWith(".zip") || name.toLowerCase().endsWith(".apk") -> unpackZip(output)
			name.toLowerCase().endsWith(".lha") -> unpackLharc(output)
			name.toLowerCase().endsWith(".7z") -> unpack7Zip(output)
			name.toLowerCase().endsWith(".rar") -> unpackRar(output)
			name.toLowerCase().endsWith(".tar") -> unpackTar(output)
			listOf(".tar.gz", ".tgz").any { name.endsWith(it) } -> unpackTarGz(output)
			name.isMusic() -> listOf(this)
			name.toLowerCase().split("/").last().split(".").first() in listOf("xm", "mod", "thx") -> {
				val paths = name.split("/")
				val path = paths.dropLast(1)
				val newFile = paths.last().split(".").let {
					it.drop(1) + it.first()
				}.joinToString(".")
				Content(entry, (path + newFile).joinToString("/"), file).getRelevantFiles(output)
			}
			name.isModule() -> listOf(this)
			name.isSid() -> listOf(this)
			name.isUrl() -> listOf(this)
			name.isVideo() -> extractAudioTracks(output)
			name.isC64Executable() -> listOf(this)
			else -> emptyList<Content>().also { this@getRelevantFiles.remove() }
		}

private const val ffmpegLocation = "/usr/local/bin/ffmpeg"
private fun Content.extractAudioTracks(output: Output): List<Content> =
		output.indent {
			val mediaFile = identify(file) ?: return@indent emptyList<Content>()
					.also { println("*** could not identify $file.") }
					.also { this@extractAudioTracks.remove() }
			return@indent when {
				mediaFile.audioTracks.isEmpty() -> {
					println("*** no audio tracks in $file: ${mediaFile.audioTracks.size}")
					emptyList()
				}
				mediaFile.audioTracks.size > 1 -> {
					println("*** invalid # of audio tracks in $file: ${mediaFile.audioTracks.size}")
					emptyList()
				}
				else -> tempFile("audio-$name-", ".${mediaFile.audioTracks[0].type.suffix}").let { tempFile ->
					runProcess(listOf(ffmpegLocation, "-y", "-i", file.toString(), "-acodec", "copy", "-vn", tempFile.toString()))
					listOf(Content(entry, "$name.${mediaFile.audioTracks.first().type.suffix}", tempFile))
				}
			}.also { this@extractAudioTracks.remove() }
		}

const val unzipLocation = "/usr/bin/unzip"
fun Content.unpackZip(output: Output) =
		unpack("zip", output) { listOf(unzipLocation, "-o", file.toString()) }

const val tarLocation = "/usr/bin/tar"
fun Content.unpackTar(output: Output) =
		unpack("tar", output) { listOf(tarLocation, "-x", "-f", file.toString()) }

fun Content.unpackTarGz(output: Output) =
		unpack("targz", output) { listOf(tarLocation, "-x", "-z", "-f", file.toString()) }

const val lharcLocation = "/usr/local/bin/lha"
fun Content.unpackLharc(output: Output) =
		unpack("lha", output) { listOf(lharcLocation, "x", file.absolutePath) }

const val sevenZipLocation = "/usr/local/bin/7z"
fun Content.unpack7Zip(output: Output) =
		unpack("7zip", output) { listOf(sevenZipLocation, "x", file.absolutePath) }

const val unrarLocation = "/usr/local/bin/unrar"
fun Content.unpackRar(output: Output) =
		unpack("rar", output) { listOf(unrarLocation, "x", file.absolutePath) }

fun Content.unpack(algorithm: String, output: Output, command: () -> List<String>) =
		tempFile("$algorithm-$name-", ".out")
				.apply {
					delete()
					mkdir()
				}
				.use { directory ->
					runProcess(command(), directory)
					runProcess(listOf("/bin/chmod", "-R", "u+w", "."), directory)
					Files.walk(directory.toPath()).toList().mapNotNull { path ->
						if (path.toFile().isDirectory) return@mapNotNull null
						if (directory.toPath().relativize(path).getName(0).toString() == "__MACOSX") return@mapNotNull null
						val destination = tempFile("file-", "-${path.fileName.toString().split("/").last()}")
						Files.move(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
						Content(entry, path.fileName.toString().replace("/", "-"), destination)
					}
							.also { this@unpack.remove() }
							.flatMap { it.getRelevantFiles(output) }
				}

fun <T> Stream<T>.toList(): List<T> = collect(Collectors.toList())

fun Content.remove() {
	file.delete()
}

fun String.isVideo() = toLowerCase()
		.split(".").last() in listOf("mp4", "m4v", "mpg", "mkv", "avi", "mov", "wmv", "flv")

fun String.isUrl() = toLowerCase().endsWith(".url")

fun String.isSid() = toLowerCase()
		.split(".").last() in listOf("sid", "psid")

fun String.isMusic() = toLowerCase()
		.split(".").last() in listOf("mp3", "ogg", "flac", "opus", "aac", "m4a", "wav", "au")

fun String.isModule() = hasModuleSuffix() || hasModulePrefix()

fun String.isC64Executable() = toLowerCase()
		.split(".").last() in listOf("prg", "d64")

fun String.hasModuleSuffix() = toLowerCase()
		.split(".").last() in listOf("xm", "mod", "digi", "dbm", "it", "s3m", "oct", "med", "ahx", "thx", "pt3", "gdm")

fun String.hasModulePrefix() = toLowerCase()
		.split("/").last()
		.split(".").first() in listOf("xm", "mod", "thx")

fun Entry.download(links: List<String>, output: Output) =
		links.mapNotNull { link ->
			tryOrNull {
				tempFile("$name-", "-${link.split("/").last()}")
						.let { link.download(it, output) ?: it.delete().let { null } }
						?.let { Content(this, link.split("/").last().decode(), it) }
			}
		}

fun String.download(destination: File, output: Output): File? =
	output.indent {
		var url = this@download
		while (true) {
			val connection = URL(url).openConnection()
			when {
				connection is HttpURLConnection && connection.responseCode >= 400 -> return@indent null
				connection is HttpURLConnection && connection.responseCode >= 300 -> url = connection.getHeaderField("Location")
				else -> return@indent tryOrNull {
					println("Downloading ${connection.contentLengthLong} Bytes...")
					destination.apply {
						outputStream().use { outputStream ->
							connection.getInputStream().use { inputStream ->
								inputStream.copyTo(outputStream)
							}
						}
					}
				}
			}
		}
		null
	}

fun String.decode() = URLDecoder.decode(this, "UTF-8")!!

fun <R> tryOrNull(silent: Boolean = true, block: () -> R): R? = try {
	block()
} catch (t: Throwable) {
	if (!silent) t.printStackTrace()
	null
}

data class Content(val entry: Entry, val name: String, val file: File) {
	override fun toString() = name
}

fun Entry.downloadLinks() =
		"https://demozoo.org/api/v1/productions/$id/"
				.httpGet()
				.header("Accept" to "application/json; charset=utf-8")
				.response()
				.takeIf { it.third.component2() == null }
				?.third?.component1()
				?.let { objectMapper.readTree(it) }
				?.let {
					it.getDownloadUrls() +
							it["external_links"]
									.filter { it["link_class"].asText() == "YoutubeVideo" }
									.map { it["url"].asText()!! }
				} ?: emptyList()

private fun JsonNode.getDownloadUrls() = this["download_links"]
		.mapNotNull(::toRecognizedUrl)

private fun toRecognizedUrl(node: JsonNode) = when (node["link_class"].asText()) {
	"SceneOrgFile" -> node["url"].asText().toSceneOrgDownloadUrl()
	else -> node["url"].asText()
}

private fun String.toSceneOrgDownloadUrl() =
		"https://archive.scene.org/pub" + removePrefix("https://files.scene.org/view")

fun Entry.base() = Paths.get("${party.name} - ${compo} - ${index.track} - $artist - $name")!!

fun Entry.directory() = Paths.get(baseDirectory,
		party.year.toString(),
		party.name,
		compo)!!

private val Demoparty.entries
	get() =
		"https://demozoo.org/api/v1/parties/$id/"
				.httpGet()
				.header("Accept" to "application/json; charset=utf-8")
				.response()
				.takeIf { it.third.component2() == null }
				?.third?.component1()
				?.let { objectMapper.readTree(it) }
				?.let { it["competitions"] }
				?.flatMap { compo ->
					compo["results"]
							.map { result ->
								Entry(
										this,
										compo["name"].asText().cleanCompo(),
										result["production"]["id"].asLong(),
										result["ranking"].asText().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0,
										result["production"]["supertype"].asText(),
										result["production"]["title"].asText().cleanTitle(),
										result["production"].artist.cleanAuthor())
							}
				} ?: emptyList()

private val Demoparty.compos
	get() = entries
			.groupBy(Entry::compo)
			.map { Compo(it.key, it.value.sortedBy { it.index }) }

private val JsonNode.artist
	get() = get("author_nicks").joinToString(" and ") { it["name"].asText() } +
			(get("author_affiliation_nicks")
					.takeIf { it.size() > 0 }
					?.joinToString(" ^ ", " ! ") { it["name"].asText() }
					?: "")

data class Compo(val name: String, val entries: List<Entry>)
data class Entry(val party: Demoparty, val compo: String, val id: Long, val index: Int, val type: String, val name: String, val artist: String)

fun String.cleanCompo() = replace(Regex("( / |/)"), " & ")
fun String.cleanTitle() = replace("/", "_")
fun String.cleanAuthor() = replace("/", "!")

private val objectMapper by lazy { ObjectMapper() }

fun getDemoparties(): Collection<Demoparty> =
		generateSequence("https://demozoo.org/api/v1/parties/" as String? to emptyList<Demoparty>()) { (url, _) ->
			url
					?.httpGet()
					?.header("Accept" to "application/json; charset=utf-8")
					?.response()
					?.takeIf { it.third.component2() == null }
					?.third?.component1()
					?.let { objectMapper.readTree(it) }
					?.let { it["next"].text to it["results"] }
					?.let { entries ->
						entries.first to entries.second.map {
							val id = it["id"].asText().toLong()
							val partyUrl = it["demozoo_url"].asText()
							val name = it["name"].asText().cleanCompo()
							val year = it["start_date"].asText().substring(0, 4).toInt()
							Demoparty(id, partyUrl, name, year)
						}
					}
		}.map { it.second }.reduce { l, r -> l + r }

private val JsonNode.text get() = takeUnless { it.isNull }?.let(JsonNode::asText)

val Int.track get() = "%02d".format(this)

data class Demoparty(val id: Long, val url: String, val name: String, val year: Int)
