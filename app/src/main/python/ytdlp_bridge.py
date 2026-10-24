import glob
import os

from yt_dlp import YoutubeDL


class _Logger:
    def debug(self, message):
        if message.startswith("[debug]"):
            return

    def warning(self, message):
        print("yt-dlp: " + str(message))

    def error(self, message):
        print("yt-dlp: " + str(message))


def download(video_id, output_dir, filename_base, progress_listener):
    os.makedirs(output_dir, exist_ok=True)

    def on_progress(event):
        status = event.get("status")
        if status == "downloading":
            total = event.get("total_bytes") or event.get("total_bytes_estimate") or 0
            downloaded = event.get("downloaded_bytes") or 0
            percent = min(99, int(downloaded * 100 / total)) if total else 0
            label = "Téléchargement audio… {}%".format(percent) if total else "Téléchargement audio…"
            progress_listener.onProgress(percent, label)
        elif status == "finished":
            progress_listener.onProgress(99, "Finalisation du fichier audio…")

    options = {
        "format": "bestaudio/best",
        "outtmpl": os.path.join(output_dir, filename_base + ".%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "logger": _Logger(),
        "progress_hooks": [on_progress],
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 30,
        "cachedir": False,
    }

    with YoutubeDL(options) as ydl:
        info = ydl.extract_info("https://www.youtube.com/watch?v=" + video_id, download=True)
        if not info:
            raise RuntimeError("yt-dlp n’a renvoyé aucun résultat pour ce morceau.")

        requested = info.get("requested_downloads") or []
        paths = [item.get("filepath") for item in requested if item.get("filepath")]
        paths.extend([info.get("_filename"), ydl.prepare_filename(info)])
        for path in paths:
            if path and os.path.isfile(path) and not path.endswith((".part", ".ytdl")):
                return path

    candidates = [path for path in glob.glob(os.path.join(output_dir, filename_base + ".*"))
                  if not path.endswith((".part", ".ytdl", ".jpg"))]
    if candidates:
        return max(candidates, key=os.path.getsize)
    raise RuntimeError("yt-dlp a terminé sans créer de fichier audio.")
