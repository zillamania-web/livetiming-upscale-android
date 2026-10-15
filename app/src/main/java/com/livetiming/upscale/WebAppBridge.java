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

                // Prefer single-file progressive (has video+audio) for WebView sound
                YoutubeDLRequest infoReq = new YoutubeDLRequest(target);
                infoReq.addOption("-f", "b*[acodec!=none][vcodec!=none]/b/bv*+ba/b");
                infoReq.addOption("--no-playlist");
                VideoInfo info = YoutubeDL.getInstance().getInfo(infoReq);

                String mediaUrl = pickBestUrl(info);
                if (mediaUrl == null || mediaUrl.isEmpty()) {
                    YoutubeDLRequest gReq = new YoutubeDLRequest(target);
                    gReq.addOption("-f", "b*[acodec!=none]/b/best");
                    gReq.addOption("-g");
                    gReq.addOption("--no-playlist");
                    YoutubeDLResponse resp = YoutubeDL.getInstance().execute(gReq, null, null);
                    String out = resp.getOut();
                    if (out != null) {
                        for (String line : out.split("\n")) {
                            line = line.trim();
                            if (line.startsWith("http")) {
                                mediaUrl = line;
                                break;
                            }
                        }
                    }
                }

                if (mediaUrl == null || mediaUrl.isEmpty()) {
                    fail("yt-dlp returned no media URL");
                    return;
                }

                String proxy = activity.registerProxy(mediaUrl);
                String title = info != null && info.getTitle() != null ? info.getTitle() : "";
                activity.evalJs(
                        "window.__onYoutubeResolved && window.__onYoutubeResolved(null," +
                                MainActivity.jsonStr(proxy) + "," +
                                MainActivity.jsonStr(title) + ")"
                );
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "yt-dlp failed";
                if (msg.length() > 180) msg = msg.substring(0, 180) + "…";
                fail(msg);
            }
        });
    }

    private void fail(String msg) {
        activity.evalJs(
                "window.__onYoutubeResolved && window.__onYoutubeResolved(" +
                        MainActivity.jsonStr(msg) + ",null,null)"
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
            boolean hasVideo = vcodec != null && !vcodec.equals("none");
            boolean hasAudio = acodec != null && !acodec.equals("none");
            if (!hasVideo) continue;

            if (height >= bestAnyH) {
                bestAnyH = height;
                bestAny = url;
            }
            if (hasAudio && height >= bestProgH) {
                bestProgH = height;
                bestProgressive = url;
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
