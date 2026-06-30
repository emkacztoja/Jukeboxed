package com.jukeboxed.auth;

public record SpotifyToken(
        String access_token,
        String refresh_token,
        long expires_at
) {
    public boolean isExpired() {
        return System.currentTimeMillis() >= expires_at;
    }
}