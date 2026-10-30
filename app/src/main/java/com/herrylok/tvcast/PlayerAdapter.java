package com.herrylok.tvcast;

import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.herrylok.tvcast.core.Playback;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Serializes all Media3 access on its application thread. */
@androidx.media3.common.util.UnstableApi
final class PlayerAdapter implements Playback {
    private final ExoPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable showPlayer;
    private volatile boolean active = true;
    private String uri = "", metadata = "";
    private int volume = 100;
    private boolean muted;
    PlayerAdapter(ExoPlayer player, Runnable showPlayer) { this.player = player; this.showPlayer = showPlayer; }
    void release() { active = false; }
    private <T> T onMain(Callable<T> action) {
        Callable<T> guarded = () -> { if (!active) throw new IllegalStateException("Receiver closed"); return action.call(); };
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return guarded.call();
            FutureTask<T> task = new FutureTask<>(guarded);
            main.post(task);
            try { return task.get(2500, TimeUnit.MILLISECONDS); }
            catch (Exception e) { task.cancel(false); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Player unavailable", e); }
    }
    @Override public State state() {
        return onMain(() -> {
            String transport;
            if (uri.isEmpty()) transport = "NO_MEDIA_PRESENT";
            else if (player.getPlayerError() != null || player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) transport = "STOPPED";
            else if (player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady()) transport = "TRANSITIONING";
            else transport = player.getPlayWhenReady() ? "PLAYING" : "PAUSED_PLAYBACK";
            long duration = player.getDuration();
            return new State(uri, metadata, transport, player.getPlayerError() == null ? "OK" : "ERROR_OCCURRED",
                    Math.max(0, player.getCurrentPosition()), duration == C.TIME_UNSET ? 0 : duration,
                    volume, muted, player.isCurrentMediaItemSeekable());
        });
    }
    @Override public void load(String uri, String metadata) {
        onMain(() -> {
            this.uri = uri; this.metadata = metadata;
            player.setPlayWhenReady(false);
            MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
            // Some apps provide extensionless HLS URLs; respect DIDL protocolInfo when supplied.
            String lower = metadata.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("application/vnd.apple.mpegurl") || lower.contains("application/x-mpegurl")) item.setMimeType("application/x-mpegURL");
            else if (lower.contains("application/dash+xml")) item.setMimeType("application/dash+xml");
            player.setMediaItem(item.build()); player.prepare(); showPlayer.run(); return null;
        });
    }
    @Override public void play() { onMain(() -> {
        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
        player.play(); showPlayer.run(); return null;
    }); }
    @Override public void pause() { onMain(() -> { player.pause(); return null; }); }
    @Override public void stop() { onMain(() -> { player.stop(); return null; }); }
    @Override public void seek(long positionMs) { onMain(() -> { player.seekTo(positionMs); return null; }); }
    @Override public void volume(int percent) { onMain(() -> { volume = percent; player.setVolume(muted ? 0f : volume / 100f); return null; }); }
    @Override public void mute(boolean mute) { onMain(() -> { muted = mute; player.setVolume(muted ? 0f : volume / 100f); return null; }); }
}
