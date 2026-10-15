package com.livetiming.upscale;

import android.webkit.JavascriptInterface;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves YouTube to a direct media URL that Android WebView can play.
 * Prefer progressive (video+audio in one file). Fall back to best single URL.
 */
public class WebAppBridge {

    private final MainActivity activity;

    public WebAppBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String platform() {
        return "android";
    }

    @JavascriptInterface
    public void showToast(String msg) {
        activity.toast(msg);
    }

    @JavascriptInterface
    public boolean isYtdlpReady() {
        return activity.isYtdlpReady();
    }

    @JavascriptInterface
    public void resolveYoutube(String pageUrl) {
        activity.io().execute(() -> {
            try {
                if (!activity.isYtdlpReady()) {
                    activity.ensureYtdlpInitialized();
                }
                String id = extractId(pageUrl);
                String target = (id != null)
                        ? "https://www.youtube.com/watch?v=" + id
                        : pageUrl.trim();

                String mediaUrl = null;
                String title = "";

                // 1) Prefer progressive MP4 — plays in WebView with sound
                mediaUrl = getUrlWithFormat(target,
                        "18/22/best[ext=mp4][acodec!=none][vcodec!=none]/best[acodec!=none][vcodec!=none]/best");

                // 2) Metadata for title + alternate URL
                try {
                    YoutubeDLRequest infoReq = new YoutubeDLRequest(target);
                    infoReq.addOption("--no-playlist");
                    infoReq.addOption("-f", "b");
                    VideoInfo info = YoutubeDL.getInstance().getInfo(infoReq);
                    if (info != null) {
                        if (info.getTitle() != null) title = info.getTitle();
                        if (mediaUrl == null || mediaUrl.isEmpty()) {
                            mediaUrl = pickBestUrl(info);
                        }
                        if ((mediaUrl == null || mediaUrl.isEmpty()) && info.getUrl() != null) {
                            mediaUrl = info.getUrl();
                        }
                    }
                } catch (Exception ignored) {}

                // 3) Last resort: plain -g best
                if (mediaUrl == null || mediaUrl.isEmpty()) {
                    mediaUrl = getUrlWithFormat(target, "best");
                }

                if (mediaUrl == null || mediaUrl.isEmpty()) {
                    fail("yt-dlp found no playable URL");
                    return;
                }

                // DIRECT URL for WebView playback (proxy was breaking video)
                String proxy = activity.registerProxy(mediaUrl);

                activity.evalJs(
                        "window.__onYoutubeResolved && window.__onYoutubeResolved(null," +
                                MainActivity.jsonStr(mediaUrl) + "," +
                                MainActivity.jsonStr(title) + "," +
                                MainActivity.jsonStr(proxy) + ")"
                );
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "yt-dlp failed";
                if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
                fail(msg);
            }
        });
    }

    private static String getUrlWithFormat(String target, String format) {
        try {
            YoutubeDLRequest gReq = new YoutubeDLRequest(target);
            gReq.addOption("-f", format);
            gReq.addOption("-g");
            gReq.addOption("--no-playlist");
            gReq.addOption("--no-warnings");
            YoutubeDLResponse resp = YoutubeDL.getInstance().execute(gReq, null, null);
            String out = resp.getOut();
            if (out == null) return null;
            String first = null;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (!line.startsWith("http")) continue;
                if (first == null) first = line;
                return line;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private void fail(String msg) {
        activity.evalJs(
                "window.__onYoutubeResolved && window.__onYoutubeResolved(" +
                        MainActivity.jsonStr(msg) + ",null,null,null)"
        );
    }

    private static String pickBestUrl(VideoInfo info) {
        if (info == null) return null;
        List<VideoFormat> formats = info.getFormats();
        if (formats == null || formats.isEmpty()) {
            return info.getUrl();
        }
        String bestProgressive = null;
        int bestProgH = -1;
        String bestAny = null;
        int bestAnyH = -1;

        for (VideoFormat f : formats) {
            String url = f.getUrl();
            if (url == null || url.isEmpty()) continue;
            Integer h = f.getHeight();
            int height = h != null ? h : 0;
            String vcodec = f.getVcodec();
            String acodec = f.getAcodec();
            String ext = f.getExt();
            boolean hasVideo = vcodec != null && !vcodec.equals("none");
            boolean hasAudio = acodec != null && !acodec.equals("none");
            if (!hasVideo) continue;

            if (height >= bestAnyH) {
                bestAnyH = height;
                bestAny = url;
            }
            if (hasAudio && height >= bestProgH) {
                boolean mp4 = ext == null || "mp4".equalsIgnoreCase(ext)
                        || url.contains("mime=video%2Fmp4") || url.contains("mime=video/mp4");
                if (mp4 || bestProgressive == null) {
                    bestProgH = height;
                    bestProgressive = url;
                }
            }
        }
        if (bestProgressive != null) return bestProgressive;
        if (bestAny != null) return bestAny;
        return info.getUrl();
    }

    static String extractId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("/(embed|shorts|live|v)/([a-zA-Z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(2);
        m = Pattern.compile("^([a-zA-Z0-9_-]{11})$").matcher(url.trim());
        if (m.find()) return m.group(1);
        return null;
    }
}
