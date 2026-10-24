package com.melodia.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MusicService extends Service {
    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "melodia_music_playback";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_TOGGLE_PAUSE = "com.melodia.app.TOGGLE_PAUSE";
    public static final String ACTION_NEXT = "com.melodia.app.NEXT";
    public static final String ACTION_PREV = "com.melodia.app.PREV";

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private NotificationManager notificationManager;

    public static class Track {
        public String title = "Nothing playing";
        public String artist = "—";
        public String path = "";
        public String imgUrl = "";
        public String videoId = "";
        public String artB64 = "";
        public String duration = "0:00";

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("title", title);
                obj.put("artist", artist);
                obj.put("path", path);
                obj.put("img_url", imgUrl);
                obj.put("video_id", videoId);
                obj.put("art_b64", artB64);
            } catch (Exception ignored) {}
            return obj;
        }
    }

    private final List<Track> queue = new ArrayList<>();
    private int currentQueueIdx = -1;
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private String currentLyrics = "";
    private Bitmap currentArtBitmap = null;
    private PlaybackListener listener;

    public interface PlaybackListener {
        void onPlaybackStateChanged();
        void onTrackChanged(Track track);
        void onLyricsUpdated(String lyrics);
    }

    public class LocalBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        initMediaPlayer();
        initMediaSession();
    }

    public void setListener(PlaybackListener l) {
        this.listener = l;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background audio playback controls");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void initMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());

            mediaPlayer.setOnCompletionListener(mp -> playNext());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                isPlaying = false;
                isPaused = false;
                notifyState();
                return true;
            });
        }
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "MelodiaMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                togglePause();
            }

            @Override
            public void onPause() {
                togglePause();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrev();
            }

            @Override
            public void onSeekTo(long pos) {
                seek((int) (pos / 1000));
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_TOGGLE_PAUSE:
                    togglePause();
                    break;
                case ACTION_NEXT:
                    playNext();
                    break;
                case ACTION_PREV:
                    playPrev();
                    break;
            }
        }
        return START_STICKY;
    }

    public synchronized void playTrack(Track track) {
        try {
            initMediaPlayer();
            mediaPlayer.reset();

            Log.d(TAG, "playTrack: " + track.title + " via path=" + (track.path.length() > 60 ? track.path.substring(0, 60) : track.path));

            if (track.path.startsWith("http://") || track.path.startsWith("https://")) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                mediaPlayer.setDataSource(this, Uri.parse(track.path), headers);
            } else if (track.path.startsWith("content://")) {
                mediaPlayer.setDataSource(this, Uri.parse(track.path));
            } else {
                File file = new File(track.path);
                if (file.exists()) {
                    mediaPlayer.setDataSource(track.path);
                } else {
                    Log.e(TAG, "Local file not found: " + track.path);
                    return;
                }
            }

            updateMediaSessionMetadata(track);
            updateNotification(track);
            notifyTrack(track);

            currentLyrics = "";
            if (listener != null) listener.onLyricsUpdated("");

            mediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "MediaPlayer prepared! Starting playback now. Duration: " + mp.getDuration());
                mp.start();
                isPlaying = true;
                isPaused = false;
                updateMediaSessionMetadata(track);
                updateMediaSessionPlaybackState();
                updateNotification(track);
                notifyState();
            });

            mediaPlayer.prepareAsync();

            // Fetch cover art & lyrics in background
            new Thread(() -> {
                currentArtBitmap = fetchBitmap(track.imgUrl);
                updateNotification(track);
                updateMediaSessionMetadata(track);

                currentLyrics = YTMusicClient.getLyrics(track.videoId, track.title, track.artist);
                if (listener != null) listener.onLyricsUpdated(currentLyrics);
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Play error: " + e.getMessage());
        }
    }

    public void queueAndPlay(Track track) {
        queue.clear();
        queue.add(track);
        currentQueueIdx = 0;
        playTrack(track);
    }

    public void addToQueue(Track track) {
        queue.add(track);
    }

    public void togglePause() {
        if (mediaPlayer == null) return;
        if (isPlaying) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                isPaused = true;
            } else {
                mediaPlayer.start();
                isPaused = false;
            }
            Track cur = getCurrentTrack();
            if (cur != null) updateNotification(cur);
            updateMediaSessionPlaybackState();
            notifyState();
        }
    }

    public void playNext() {
        if (queue.isEmpty()) return;
        if (currentQueueIdx < queue.size() - 1) {
            currentQueueIdx++;
            playTrack(queue.get(currentQueueIdx));
        } else {
            currentQueueIdx = 0;
            playTrack(queue.get(0));
        }
    }

    public void playPrev() {
        if (queue.isEmpty()) return;
        if (currentQueueIdx > 0) {
            currentQueueIdx--;
            playTrack(queue.get(currentQueueIdx));
        } else {
            playTrack(queue.get(0));
        }
    }

    public void seek(int seconds) {
        if (mediaPlayer != null && isPlaying) {
            mediaPlayer.seekTo(seconds * 1000);
            updateMediaSessionPlaybackState();
            notifyState();
        }
    }

    public void setVolume(float vol) {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(vol, vol);
        }
    }

    public Track getCurrentTrack() {
        if (currentQueueIdx >= 0 && currentQueueIdx < queue.size()) {
            return queue.get(currentQueueIdx);
        }
        return null;
    }

    public List<Track> getQueue() {
        return queue;
    }

    public int getCurrentQueueIdx() {
        return currentQueueIdx;
    }

    public void clearQueue() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
        queue.clear();
        currentQueueIdx = -1;
        isPlaying = false;
        isPaused = false;
        stopForeground(true);
        notifyState();
    }

    public void stopIfPlayingPath(String path) {
        Track current = getCurrentTrack();
        if (current != null && current.path.equals(path)) clearQueue();
    }

    public void removeFromQueue(int idx) {
        if (idx >= 0 && idx < queue.size()) {
            queue.remove(idx);
            if (idx < currentQueueIdx) {
                currentQueueIdx--;
            }
        }
    }

    public void jumpToQueue(int idx) {
        if (idx >= 0 && idx < queue.size()) {
            currentQueueIdx = idx;
            playTrack(queue.get(idx));
        }
    }

    public JSONObject getPlayerState() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("is_playing", isPlaying);
            obj.put("is_paused", isPaused);
            Track cur = getCurrentTrack();
            obj.put("title", cur != null ? cur.title : "Nothing playing");
            obj.put("artist", cur != null ? cur.artist : "—");
            double dur = 0;
            double pos = 0;
            if (mediaPlayer != null) {
                try {
                    dur = mediaPlayer.getDuration() / 1000.0;
                    pos = mediaPlayer.getCurrentPosition() / 1000.0;
                } catch (Exception ignored) {}
            }
            obj.put("duration", dur);
            obj.put("position", pos);
            obj.put("art_b64", cur != null ? cur.artB64 : null);
            obj.put("lyrics", currentLyrics);
        } catch (Exception ignored) {}
        return obj;
    }

    public String getCurrentLyrics() {
        return currentLyrics;
    }

    private void updateMediaSessionMetadata(Track track) {
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (mediaPlayer != null && isPlaying) ? mediaPlayer.getDuration() : 0);
        if (currentArtBitmap != null) {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtBitmap);
        }
        mediaSession.setMetadata(b.build());
    }

    private void updateMediaSessionPlaybackState() {
        int state = (isPlaying && !isPaused) ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long pos = (mediaPlayer != null && isPlaying) ? mediaPlayer.getCurrentPosition() : 0;
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, pos, 1.0f);
        mediaSession.setPlaybackState(b.build());
    }

    private void updateNotification(Track track) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevIntent = PendingIntent.getService(this, 1,
                new Intent(this, MusicService.class).setAction(ACTION_PREV), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pauseIntent = PendingIntent.getService(this, 2,
                new Intent(this, MusicService.class).setAction(ACTION_TOGGLE_PAUSE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextIntent = PendingIntent.getService(this, 3,
                new Intent(this, MusicService.class).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE);

        int playPauseIcon = (isPlaying && !isPaused) ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        androidx.media.app.NotificationCompat.MediaStyle mediaStyle = new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(mediaStyle)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
                .addAction(playPauseIcon, "Play/Pause", pauseIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
                .setOngoing(isPlaying && !isPaused);

        if (currentArtBitmap != null) {
            builder.setLargeIcon(currentArtBitmap);
        }

        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private Bitmap fetchBitmap(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoInput(true);
            conn.connect();
            InputStream is = conn.getInputStream();
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyState() {
        if (listener != null) listener.onPlaybackStateChanged();
    }

    private void notifyTrack(Track t) {
        if (listener != null) listener.onTrackChanged(t);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        clearQueue();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopForeground(true);
    }
}
