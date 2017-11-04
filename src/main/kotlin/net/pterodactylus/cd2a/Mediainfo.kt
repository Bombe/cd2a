package net.pterodactylus.cd2a

import net.pterodactylus.cd2a.VideoType.DV
import net.pterodactylus.cd2a.VideoType.MPEG2
import net.pterodactylus.cd2a.VideoType.MPEG4
import net.pterodactylus.cd2a.VideoType.OTHER
import net.pterodactylus.cd2a.VideoType.RAW
import net.pterodactylus.cd2a.VideoType.WMV
import net.pterodactylus.cd2a.VideoType.XVID
import java.io.File

enum class VideoType { MPEG4, MPEG2, XVID, WMV, DV, RAW, OTHER }
data class VideoTrack(val type: VideoType)

data class AudioTrack(val type: AudioType)

data class MediaFile(val videoTracks: List<VideoTrack>, val audioTracks: List<AudioTrack>)

private val mediainfoLocation = "/usr/local/bin/mediainfo"

private data class FoldResult(val videoCodecs: List<String> = emptyList(), val audioCodecs: List<AudioType> = emptyList(), val fields: Map<String, String> = emptyMap(), val inVideo: Boolean = false, val inAudio: Boolean = false)

fun identify(file: File): MediaFile? =
		tempFile("stdout-", ".txt").use { tempFile ->
			runProcess(listOf(mediainfoLocation, file.toString()), stdout = tempFile)
			tempFile.readLines()
					.fold(FoldResult()) { previous, currentLine ->
						currentLine.split(":")
								.let { it.first().trim() to it.drop(1).joinToString(":").trim() }
								.let { (key, value) ->
									when {
										key.isEmpty() && value.isEmpty() ->
											when {
												previous.inVideo && "Codec ID" in previous.fields ->
													previous.copy(inVideo = false, fields = emptyMap(), videoCodecs = previous.videoCodecs + previous.fields["Codec ID"]!!)
												previous.inAudio ->
													previous.copy(inAudio = false, fields = emptyMap(), audioCodecs = identifyAudioCode(
															previous.fields["Format"],
															previous.fields["Format version"],
															previous.fields["Format profile"],
															previous.fields["Codec ID"]
													)?.let { previous.audioCodecs + it } ?: previous.audioCodecs)
												else -> previous.copy(fields = emptyMap())
											}
										key == "Video" && value.isEmpty() -> previous.copy(inVideo = true, inAudio = false)
										key == "Audio" && value.isEmpty() -> previous.copy(inVideo = false, inAudio = true)
										else -> previous.copy(fields = previous.fields + (key to value))
									}
								}
					}
					.let {
						if (it.videoCodecs.isEmpty() && it.audioCodecs.isEmpty())
							null
						else
							MediaFile(
									it.videoCodecs.mapNotNull(videoCodecMap::get).map(::VideoTrack),
									it.audioCodecs.map(::AudioTrack)
							)
					}
		}

private val videoCodecMap = mapOf(
		"avc1" to MPEG4,
		"V_MPEG4/ISO/AVC" to MPEG4,
		"H264" to MPEG4,
		"20" to MPEG4,
		"mpg2" to MPEG2,
		"XVID" to XVID,
		"WVC1" to WMV,
		"WMV3" to WMV,
		"dvsd" to DV,
		"FPS1" to OTHER, // FRAPS
		"tscc" to OTHER, // TechSmith Screen Capture
		"apcn" to OTHER, // Apple ProRes Codec
		"0x00000000" to RAW,
		"0x00000001" to RAW
)

enum class AudioType(val suffix: String) {
	MP2("mp2") {
		override fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
				(format == "MPEG Audio" && formatVersion == "Version 1" && formatProfile == "Layer 2")
	},
	MP3("mp3") {
		private val allowedCodecIds = listOf("A_MPEG/L3", "55", "6B")
		override fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
				codecId in allowedCodecIds
	},
	AAC("m4a") {
		private val allowedCodecIds = listOf("mp4a-40-2", "A_AAC-2")
		override fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
				codecId in allowedCodecIds
	},
	AC3("ac3") {
		private val allowedCodecIds = listOf("ac-3", "00001000-0000-0020-8000-00AA00389B71")
		override fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
				codecId in allowedCodecIds
	},
	PCM("wav") {
		private val allowedCodecIds = listOf("twos", "sowt", "lpcm", "1")
		override fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
				codecId in allowedCodecIds
	},
	WMA("wma") {
		private val allowedCodecIds = listOf("161", "162")
		override fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
				codecId in allowedCodecIds
	};

	abstract fun matches(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?): Boolean
}

private fun identifyAudioCode(format: String?, formatVersion: String?, formatProfile: String?, codecId: String?) =
		AudioType.values().firstOrNull { it.matches(format, formatVersion, formatProfile, codecId) }
