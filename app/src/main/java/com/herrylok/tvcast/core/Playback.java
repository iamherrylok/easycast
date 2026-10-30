package com.herrylok.tvcast.core;

/** All methods may be called off the UI thread; the Android adapter owns dispatch. */
public interface Playback {
    State state();
    void load(String uri, String metadata);
    void play();
    void pause();
    void stop();
    void seek(long positionMs);
    void volume(int percent);
    void mute(boolean mute);

    final class State {
        public final String uri, metadata, transport, status;
        public final long position, duration;
        public final int volume;
        public final boolean muted, seekable;
        public State(String uri, String metadata, String transport, String status,
                     long position, long duration, int volume, boolean muted, boolean seekable) {
            this.uri = uri; this.metadata = metadata; this.transport = transport; this.status = status;
            this.position = position; this.duration = duration; this.volume = volume;
            this.muted = muted; this.seekable = seekable;
        }
    }
}
