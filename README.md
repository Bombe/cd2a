# cd2a - Convert Demoparties to Audio

This tool was created in order to support me with collecting all kinds of audio files from past demoparties. The manual process (using the excellent [demozoo.org](https://demozoo.org/) website) was tedious and error-prone.

If you decide to use this tool, please do so responsibly: at the moment it probably creates a lot of load on both the Demozoo server and on the scene.org server in the Netherlands.

## What It Currently Does

1. Uses [Demozoo’s Party List API](https://demozoo.org/api/v1/parties/) to collect all demoparties and their year.
2. Uses Demozoo’s Party API to collect the compos and entries for all selected demoparties. (This selection is hard-coded.)
3. Uses Demozoo’s Production API to get download and YouTube links for all “music” and “production” entries. (Actually, for everything but “graphics” entries.)
4. For an entry, check if there is already something on the disk matching the destination name pattern. If there is, proceed to next entry.
5. Downloads whatever has been linked.
6. If it’s an archive (.zip, .7z, .lha, .rar, .tar, .tar.gz) it unpacks it.
7. If it’s a video file, the audio track is extracted.
8. If it’s a MOD file or other kind of audio file, keep it.
9. If it’s a C64 executable (.d64, .prg) or a SID file (.sid, .psid), keep it.
10. Anything else is deleted.
11. If nothing is left and there is a YouTube link, the audio track is downloaded from YouTube.
12. Anything that is still there is stored in a directory named `<year>/<party>/<compo>/`.

Steps 6–10 are done recursively, i.e. if an MP3 file is hidden in a .zip inside a .tar.gz it will nontheless be found. If multiple files can be extracted for an entry they are automatically numbered with the common “(2)” appendix.

## What It Currently Does Not Do

2. It does not convert audio types at the moment. WAV files (and others) are left as the “final” product.
3. It does not show progress in a very nice way.
4. It does not memorize entries for which it could not extract any data; if there is not yet anything on the disc for that entry, the entry will be downloaded again even if repeating the extraction process will not produce any new results.
5. It does not offer any user interface; if you do not want to download _every entry from every demoparty there ever was_ you have to modify the code.
6. You have no possibility to change the pattern of the generated files and directories—unless you modify the code.
7. It expects a lot of binaries in hardcoded locations. No way to change that—unless you modify the code.
