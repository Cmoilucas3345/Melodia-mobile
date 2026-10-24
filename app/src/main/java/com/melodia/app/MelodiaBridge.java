package com.melodia.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import androidx.documentfile.provider.DocumentFile;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MelodiaBridge {
    private static final String TAG = "MelodiaBridge";
    private final MainActivity activity;
    private final SharedPreferences prefs;

    public interface YtDlpProgressListener {
        void onProgress(int percent, String status);
    }

    public MelodiaBridge(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("melodia_user_data", Context.MODE_PRIVATE);
    }

    private MusicService getService() {
        return activity.getMusicService();
    }

    private File getAppDownloadDirectory() {
        File dir = new File(activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Melodia");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private Uri getDownloadTreeUri() {
        String saved = prefs.getString("download_tree_uri", "");
        return saved.isEmpty() ? null : Uri.parse(saved);
    }

    private DocumentFile getDownloadTree() {
        Uri uri = getDownloadTreeUri();
        return uri == null ? null : DocumentFile.fromTreeUri(activity, uri);
    }

    private void refreshSettingsView() {
        activity.runOnUiThread(() -> {
            if (activity.getWebView() != null) {
                activity.getWebView().evaluateJavascript("if(window.Settings) Settings.load();", null);
            }
        });
    }

    public void onDownloadDirectorySelected(String uri) {
        prefs.edit().putString("download_tree_uri", uri).apply();
        refreshSettingsView();
    }

    @JavascriptInterface
    public void chooseDownloadDirectory() {
        activity.runOnUiThread(activity::openDownloadDirectoryPicker);
    }

    @JavascriptInterface
    public void resetDownloadDirectory() {
        Uri oldUri = getDownloadTreeUri();
        prefs.edit().remove("download_tree_uri").apply();
        if (oldUri != null) {
            try {
                activity.getContentResolver().releasePersistableUriPermission(oldUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        refreshSettingsView();
    }

    @JavascriptInterface
    public String getRecommendations() {
        List<YTMusicClient.TrackInfo> tracks = YTMusicClient.getRecommendations();
        JSONArray arr = new JSONArray();
        for (YTMusicClient.TrackInfo t : tracks) {
            arr.put(t.toJson());
        }
        return arr.toString();
    }

    @JavascriptInterface
    public String getMoreRecommendations() {
        List<YTMusicClient.TrackInfo> tracks = YTMusicClient.searchSongs("Trending Music");
        JSONArray arr = new JSONArray();
        for (YTMusicClient.TrackInfo t : tracks) {
            arr.put(t.toJson());
        }
        return arr.toString();
    }

    @JavascriptInterface
    public String searchSongs(String query) {
        List<YTMusicClient.TrackInfo> tracks = YTMusicClient.searchSongs(query);
        JSONArray arr = new JSONArray();
        for (YTMusicClient.TrackInfo t : tracks) {
            arr.put(t.toJson());
        }
        return arr.toString();
    }

    @JavascriptInterface
    public void cloudPlay(String videoId, String title, String artist, String thumb) {
        new Thread(() -> {
            activity.pushDownloadProgress(true, 0, title, artist, "Préparation du téléchargement…", "");
            String safe = (title + " - " + artist).replaceAll("[^a-zA-Z0-9 _-]", "").trim();
            try {
                File stagingDir = getAppDownloadDirectory();
                DocumentFile tree = getDownloadTree();
                File existing = tree == null ? findDownloadedTrack(stagingDir, safe) : null;
                DocumentFile existingDocument = tree == null ? null : findDocumentTrack(tree, safe);
                if (existing != null || existingDocument != null) {
                    String path = existing != null ? existing.getAbsolutePath() : existingDocument.getUri().toString();
                    playLocalFile(path, title, artist, thumb, videoId);
                    activity.pushDownloadProgress(false, 100, title, artist, "Déjà disponible hors ligne", "");
                    return;
                }

                File downloaded = downloadWithYtDlp(videoId, stagingDir, safe, title, artist);
                downloadCoverArt(thumb, downloaded);
                String playbackPath = downloaded.getAbsolutePath();
                if (tree != null) {
                    DocumentFile savedAudio = copyIntoTree(tree, downloaded, mimeTypeFor(downloaded.getName()));
                    copySidecarIntoTree(tree, downloaded);
                    playbackPath = savedAudio.getUri().toString();
                    downloaded.delete();
                    new File(stagingDir, stripExtension(downloaded.getName()) + ".jpg").delete();
                }
                playLocalFile(playbackPath, title, artist, thumb, videoId);
                activity.pushDownloadProgress(false, 100, title, artist, "Téléchargement terminé avec yt-dlp", "");
            } catch (Exception e) {
                String reason = readableError(e);
                Log.w(TAG, "yt-dlp download error: " + reason, e);
                activity.pushDownloadProgress(false, 0, title, artist, "Échec du téléchargement yt-dlp", reason);
            }
        }).start();
    }

    private File findDownloadedTrack(File dir, String filenameBase) {
        String[] extensions = {"m4a", "mp3", "webm", "opus", "ogg"};
        for (String extension : extensions) {
            File candidate = new File(dir, filenameBase + "." + extension);
            if (candidate.isFile() && candidate.length() > 0) return candidate;
        }
        return null;
    }

    private DocumentFile findDocumentTrack(DocumentFile tree, String filenameBase) {
        for (String extension : new String[]{"m4a", "mp3", "webm", "opus", "ogg"}) {
            DocumentFile candidate = tree.findFile(filenameBase + "." + extension);
            if (candidate != null && candidate.isFile() && candidate.length() > 0) return candidate;
        }
        return null;
    }

    private boolean isAudioFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".webm") ||
                lower.endsWith(".opus") || lower.endsWith(".ogg");
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String mimeTypeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "audio/webm";
    }

    private DocumentFile copyIntoTree(DocumentFile tree, File source, String mimeType) throws IOException {
        DocumentFile existing = tree.findFile(source.getName());
        if (existing != null) existing.delete();
        DocumentFile target = tree.createFile(mimeType, source.getName());
        if (target == null) throw new IOException("Android n’a pas pu créer le fichier dans le dossier choisi.");
        try (InputStream input = new FileInputStream(source);
             java.io.OutputStream output = activity.getContentResolver().openOutputStream(target.getUri(), "w")) {
            if (output == null) throw new IOException("Impossible d’écrire dans le dossier choisi.");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        } catch (Exception e) {
            target.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Échec de la copie vers le dossier choisi : " + e.getMessage(), e);
        }
        return target;
    }

    private void copySidecarIntoTree(DocumentFile tree, File audioFile) throws IOException {
        File art = new File(audioFile.getParentFile(), stripExtension(audioFile.getName()) + ".jpg");
        if (art.isFile()) copyIntoTree(tree, art, "image/jpeg");
    }

    private String getDownloadDirectoryLabel() {
        Uri uri = getDownloadTreeUri();
        DocumentFile tree = getDownloadTree();
        if (tree == null) return getAppDownloadDirectory().getAbsolutePath();
        try {
            String documentId = android.provider.DocumentsContract.getTreeDocumentId(uri);
            return documentId.replace(':', '/');
        } catch (Exception ignored) {
            String name = tree.getName();
            return name == null || name.isEmpty() ? "Dossier sélectionné" : name;
        }
    }

    private String playbackPathFor(String filename) {
        Uri treeUri = getDownloadTreeUri();
        if (treeUri == null) {
            File file = new File(getAppDownloadDirectory(), filename);
            return file.isFile() ? file.getAbsolutePath() : null;
        }
        DocumentFile tree = DocumentFile.fromTreeUri(activity, treeUri);
        DocumentFile file = tree != null ? tree.findFile(filename) : null;
        return file != null && file.isFile() ? file.getUri().toString() : null;
    }

    private File downloadWithYtDlp(String videoId, File dir, String filenameBase, String title, String artist) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(activity.getApplication()));
        }
        YtDlpProgressListener listener = (percent, status) ->
                activity.pushDownloadProgress(true, percent, title, artist, status, "");
        PyObject result = Python.getInstance().getModule("ytdlp_bridge")
                .callAttr("download", videoId, dir.getAbsolutePath(), filenameBase, listener);
        File downloaded = new File(result.toString());
        if (!downloaded.isFile() || downloaded.length() == 0) {
            throw new IllegalStateException("yt-dlp n’a pas créé de fichier audio lisible.");
        }
        return downloaded;
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "Pas de connexion Internet (serveur introuvable).";
        if (e instanceof java.net.SocketTimeoutException) return "Le serveur a mis trop de temps à répondre.";
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message;
    }

    private void downloadCoverArt(String thumbUrl, File audioFile) {
        if (thumbUrl == null || thumbUrl.isEmpty()) return;
        String name = audioFile.getName();
        int extensionAt = name.lastIndexOf('.');
        String baseName = extensionAt > 0 ? name.substring(0, extensionAt) : name;
        File imageFile = new File(audioFile.getParentFile(), baseName + ".jpg");
        okhttp3.Request request = new okhttp3.Request.Builder().url(thumbUrl).header("User-Agent", "Mozilla/5.0").build();
        try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(imageFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Cover art download failed: " + readableError(e));
        }
    }

    private void playLocalFile(String path, String title, String artist, String thumb, String videoId) {
        MusicService.Track t = new MusicService.Track();
        t.title = title;
        t.artist = artist;
        t.path = path;
        t.imgUrl = thumb;
        t.videoId = videoId;
        if (getService() != null) {
            getService().queueAndPlay(t);
        }
    }

    @JavascriptInterface
    public String getLibrary() {
        JSONArray arr = new JSONArray();
        Set<String> favs = prefs.getStringSet("favorites", new HashSet<>());
        DocumentFile tree = getDownloadTree();
        if (tree != null) {
            DocumentFile[] files = tree.listFiles();
            for (DocumentFile file : files) {
                if (file.isFile() && file.getName() != null && isAudioFile(file.getName())) {
                    addLibraryItem(arr, file.getName(), favs, tree);
                }
            }
        } else {
            File[] files = getAppDownloadDirectory().listFiles((d, name) -> isAudioFile(name));
            if (files != null) {
                for (File file : files) addLibraryItem(arr, file.getName(), favs, null);
            }
        }
        return arr.toString();
    }

    private void addLibraryItem(JSONArray arr, String name, Set<String> favs, DocumentFile tree) {
        try {
            String base = stripExtension(name);
            String[] parts = base.split("-", 2);
            JSONObject item = new JSONObject();
            item.put("filename", name);
            item.put("title", parts[0].trim());
            item.put("artist", parts.length > 1 ? parts[1].trim() : "Unknown");
            item.put("is_fav", favs.contains(name));

            String artName = base + ".jpg";
            if (tree == null) {
                File artFile = new File(getAppDownloadDirectory(), artName);
                if (artFile.isFile()) {
                    try (InputStream input = new FileInputStream(artFile)) {
                        byte[] bytes = new byte[(int) artFile.length()];
                        int offset = 0, read;
                        while (offset < bytes.length && (read = input.read(bytes, offset, bytes.length - offset)) != -1) offset += read;
                        item.put("art_b64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    }
                }
            } else {
                DocumentFile artFile = tree.findFile(artName);
                if (artFile != null && artFile.isFile() && artFile.length() <= 8 * 1024 * 1024) {
                    try (InputStream input = activity.getContentResolver().openInputStream(artFile.getUri())) {
                        if (input != null) {
                            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                            item.put("art_b64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                        }
                    }
                }
            }
            arr.put(item);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void playLocal(String filename) {
        String path = playbackPathFor(filename);
        if (path != null) {
            String base = stripExtension(filename);
            String[] parts = base.split("-", 2);
            String title = parts[0].trim();
            String artist = parts.length > 1 ? parts[1].trim() : "Unknown";
            playLocalFile(path, title, artist, "", "");
        }
    }

    @JavascriptInterface
    public void enqueueLocal(String filename) {
        String path = playbackPathFor(filename);
        if (path != null) {
            String base = stripExtension(filename);
            String[] parts = base.split("-", 2);
            MusicService.Track t = new MusicService.Track();
            t.title = parts[0].trim();
            t.artist = parts.length > 1 ? parts[1].trim() : "Unknown";
            t.path = path;
            if (getService() != null) {
                getService().addToQueue(t);
            }
        }
    }

    @JavascriptInterface
    public boolean deleteTrack(String filename) {
        if (filename == null || !filename.equals(new File(filename).getName())) return false;
        String path = playbackPathFor(filename);
        if (path == null) return false;
        boolean deleted;
        Uri treeUri = getDownloadTreeUri();
        if (treeUri == null) {
            File file = new File(getAppDownloadDirectory(), filename);
            deleted = !file.exists() || file.delete();
            File art = new File(getAppDownloadDirectory(), stripExtension(filename) + ".jpg");
            if (art.exists()) art.delete();
        } else {
            DocumentFile tree = DocumentFile.fromTreeUri(activity, treeUri);
            DocumentFile file = tree != null ? tree.findFile(filename) : null;
            deleted = file != null && file.delete();
            DocumentFile art = tree != null ? tree.findFile(stripExtension(filename) + ".jpg") : null;
            if (art != null) art.delete();
        }
        if (deleted && getService() != null) getService().stopIfPlayingPath(path);
        return deleted;
    }

    @JavascriptInterface
    public void toggleFavorite(String filename) {
        Set<String> favs = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
        if (favs.contains(filename)) favs.remove(filename);
        else favs.add(filename);
        prefs.edit().putStringSet("favorites", favs).apply();
    }

    @JavascriptInterface
    public String getPlaylists() {
        return prefs.getString("playlists", "{}");
    }

    @JavascriptInterface
    public void createPlaylist(String name, String filesJson) {
        try {
            JSONObject playlists = new JSONObject(prefs.getString("playlists", "{}"));
            playlists.put(name, new JSONArray(filesJson));
            prefs.edit().putString("playlists", playlists.toString()).apply();
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void deletePlaylist(String name) {
        try {
            JSONObject playlists = new JSONObject(prefs.getString("playlists", "{}"));
            playlists.remove(name);
            prefs.edit().putString("playlists", playlists.toString()).apply();
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void playPlaylist(String name, boolean shuffle) {
        try {
            JSONObject playlists = new JSONObject(prefs.getString("playlists", "{}"));
            if (playlists.has(name)) {
                JSONArray files = playlists.getJSONArray(name);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < files.length(); i++) {
                    list.add(files.getString(i));
                }
                if (shuffle) Collections.shuffle(list);

                if (getService() != null && !list.isEmpty()) {
                    getService().clearQueue();
                    for (String fname : list) {
                        String path = playbackPathFor(fname);
                        if (path != null) {
                            String base = stripExtension(fname);
                            String[] parts = base.split("-", 2);
                            MusicService.Track t = new MusicService.Track();
                            t.title = parts[0].trim();
                            t.artist = parts.length > 1 ? parts[1].trim() : "Unknown";
                            t.path = path;
                            getService().addToQueue(t);
                        }
                    }
                    if (!getService().getQueue().isEmpty()) {
                        getService().jumpToQueue(0);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String getQueue() {
        JSONObject res = new JSONObject();
        JSONArray arr = new JSONArray();
        if (getService() != null) {
            for (MusicService.Track t : getService().getQueue()) {
                arr.put(t.toJson());
            }
            try {
                res.put("queue", arr);
                res.put("idx", getService().getCurrentQueueIdx());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public void clearQueue() {
        if (getService() != null) getService().clearQueue();
    }

    @JavascriptInterface
    public void removeFromQueue(int idx) {
        if (getService() != null) getService().removeFromQueue(idx);
    }

    @JavascriptInterface
    public void jumpTo(int idx) {
        if (getService() != null) getService().jumpToQueue(idx);
    }

    @JavascriptInterface
    public void togglePause() {
        if (getService() != null) getService().togglePause();
    }

    @JavascriptInterface
    public void next() {
        if (getService() != null) getService().playNext();
    }

    @JavascriptInterface
    public void prev() {
        if (getService() != null) getService().playPrev();
    }

    @JavascriptInterface
    public void seek(float seconds) {
        if (getService() != null) getService().seek((int) seconds);
    }

    @JavascriptInterface
    public void setVolume(float vol) {
        if (getService() != null) getService().setVolume(vol);
    }

    @JavascriptInterface
    public String getLyrics(String videoId, String title, String artist) {
        return YTMusicClient.getLyrics(videoId, title, artist);
    }

    @JavascriptInterface
    public String getPlayerState() {
        if (getService() != null) {
            return getService().getPlayerState().toString();
        }
        return "{}";
    }

    @JavascriptInterface
    public String getSettings() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("auto_play", prefs.getBoolean("auto_play", true));
            obj.put("run_in_background", prefs.getBoolean("run_in_background", true));
            obj.put("download_dir", getDownloadDirectoryLabel());
        } catch (Exception ignored) {}
        return obj.toString();
    }

    @JavascriptInterface
    public void saveSetting(String key, String val) {
        if ("auto_play".equals(key)) {
            prefs.edit().putBoolean("auto_play", Boolean.parseBoolean(val)).apply();
        } else if ("run_in_background".equals(key)) {
            prefs.edit().putBoolean("run_in_background", Boolean.parseBoolean(val)).apply();
        }
    }

    @JavascriptInterface
    public String getDownloadDir() {
        return getDownloadDirectoryLabel();
    }
}
