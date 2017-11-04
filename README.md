# cd2a - Convert Demoparties to Audio

This tool was created in order to support me with collecting all kinds of audio files from past demoparties. The manual process (using the excellent [demozoo.org](https://demozoo.org/) website) was tedious and error-prone.

If you decide to use this tool, please do so responsibly: at the moment it probably creates a lot of load on both the Demozoo server and on the scene.org server which is listed as first secondary (for most entries that is probably nl-ftp).

## What It Currently Does

1. Scrapes [Demozoo’s page of parties](https://demozoo.org/parties/by_date/) to collect all demoparties and their year.
* Scrapes the page of all selected demoparties for the compos and entries.
* Scrapes the pages of all “music” and “production” entries for download and YouTube links.
* For an entry, check if there is already something on the disk matching the destination name pattern. If there is, proceed to next entry.
* Downloads whatever has been linked.
* If it’s an archive (.zip, .7z, .lha, .rar, .tar, .tar.gz) it unpacks it.
* If it’s a video file, the audio track is extracted.
* If it’s a MOD file or other kind of audio file, keep it.
* If it’s a C64 executable (.d64, .prg) or a SID file (.sid, .psid), keep it.
* Anything else is deleted.
* If nothing is left and there is a YouTube link, the audio track is downloaded from YouTube.
* Anything that is still there is stored in a directory named `<year>/<party>/<compo>/`.

Steps 6–10 are done recursively, i.e. if an MP3 file is hidden in a .zip inside a .tar.gz it will nontheless be found. If multiple files can be extracted for an entry they are automatically numbered with the common “(2)” appendix.

## What It Currently Does Not Do

* It does not use Demozoo’s REST API for finding parties, their compos, and their entries. This is because this API does not yet exist. [Issue #323](https://github.com/demozoo/demozoo/issues/323) has been created.
* It does not convert audio types at the moment. WAV files (and others) are left as the “final” product.
* It does not show progress in a very nice way.
* It does not memorize entries for which it could not extract any data; if there is not yet anything on the disc for that entry, the entry will be downloaded again even if repeating the extraction process will not produce any new results.
* It does not offer any user interface; if you do not want to download _every entry from every demoparty there ever was_ you have to modify the code.
