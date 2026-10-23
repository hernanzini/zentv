package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ZenTV's only HTTP client, for the weather and the daily wallpaper: HTTPS GET with short timeouts
 * and a size cap, so a slow or odd server can neither hang a download for long nor fill the TV's memory.
 * Blocking: call it off the main thread.
 */
final class ZenNet {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private ZenNet() {
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not an https URL: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "TvHome");
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " from " + c.getURL().getHost());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > maxBytes) throw new IOException("answer larger than " + maxBytes + " bytes");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
