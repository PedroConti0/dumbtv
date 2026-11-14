package tv.dumbtv.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Netflix's public weekly country Top 10 list, descriptions and high-resolution title artwork. */
final class NetflixTopTen {
    private static final String TAG = "dumbtv-top10";
    private static final String PACKAGE = "com.netflix.ninja";
    private static final String SOURCE = "Netflix";
    private static final long REFRESH_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_HTML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MIN_IMAGE_WIDTH = 1000;
    private static final Pattern IMAGE = Pattern.compile("(?is)<img\\b([^>]+)>");
    private static final Pattern META = Pattern.compile("(?is)<meta\\b([^>]+)>");
    private static final Pattern ATTR = Pattern.compile("(?is)([a-z_:][-a-z0-9_:]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern WATCH = Pattern.compile("(?i)(?:/watch/|/title/)([0-9]{5,})");
    private static final Pattern SRCSET = Pattern.compile("(?is)(https?://[^\\s,]+|//[^\\s,]+)(?:\\s+(\\d+)w)?");

    private NetflixTopTen() {}

    static List<Library.Entry> load(Context context, String country) {
        File dir = new File(context.getFilesDir(), "netflix-top10-v2");
        if (!dir.exists() && !dir.mkdirs()) return new ArrayList<>();
        String slug = slug(country);
        File marker = new File(dir, "updated-" + slug);
        List<Library.Entry> cached = readCache(context, dir, slug);
        if (!cached.isEmpty() && marker.isFile()
                && System.currentTimeMillis() - marker.lastModified() < REFRESH_MS)
            return cached;

        try {
            String address = "https://www.netflix.com/tudum/top10/" + slug + "/tv";
            String html = get(address, MAX_HTML_BYTES);
            List<Card> cards = parse(html);
            if (cards.size() > 10) cards = cards.subList(0, 10);
            Log.i(TAG, "Parsed " + cards.size() + " ranked cards from Netflix page " + address);
            if (cards.isEmpty()) {
                Log.w(TAG, "No title cards parsed from " + address);
                return cached;
            }
            PackageManager pm = context.getPackageManager();
            CharSequence appLabel;
            try {
                if (!pm.getApplicationInfo(PACKAGE, 0).enabled) return cached;
                appLabel = pm.getApplicationInfo(PACKAGE, 0).loadLabel(pm);
            } catch (PackageManager.NameNotFoundException missing) {
                return cached;
            }

            ExecutorService pool = Executors.newFixedThreadPool(4);
            ExecutorCompletionService<Library.Entry> completed = new ExecutorCompletionService<>(pool);
            for (Card card : cards) completed.submit(() -> loadCard(dir, card, appLabel));
            List<Library.Entry> fresh = new ArrayList<>();
            try {
                for (int i = 0; i < cards.size(); i++) {
                    Library.Entry e = completed.take().get();
                    if (e != null) fresh.add(e);
                }
            } finally {
                pool.shutdownNow();
            }
            if (fresh.isEmpty()) {
                Log.w(TAG, "No high-resolution poster images were loaded from Netflix Top 10");
                return cached;
            }
            // Restore the official ranking order after the parallel title-page and image requests.
            Map<String, Library.Entry> byId = new LinkedHashMap<>();
            for (Library.Entry e : fresh) byId.put(titleId(e), e);
            fresh.clear();
            Set<String> seenShows = new HashSet<>();
            for (Card card : cards) {
                Library.Entry e = byId.get(card.id);
                if (e != null && seenShows.add(seriesKey(e.title))) fresh.add(e);
            }
            marker.setLastModified(System.currentTimeMillis());
            writeCache(dir, slug, fresh);
            Log.i(TAG, "Loaded " + fresh.size() + " Netflix Top 10 titles for " + country);
            return fresh;
        } catch (Exception unavailable) {
            Log.w(TAG, "Could not refresh Netflix Top 10 for " + country, unavailable);
            return cached;
        }
    }

    private static Library.Entry loadCard(File dir, Card card, CharSequence appLabel) {
        try {
            Detail detail = new Detail();
            try {
                detail = parseDetail(get("https://www.netflix.com/title/" + card.id, MAX_HTML_BYTES));
            } catch (Exception metadataUnavailable) {
                Log.w(TAG, "Netflix title metadata unavailable for title id " + card.id, metadataUnavailable);
            }
            File image = new File(dir, String.format(Locale.ROOT, "%02d-%s.jpg", card.rank, card.id));
            boolean ready = isLargeImage(image);
            if (!ready) {
                List<String> sources = new ArrayList<>(detail.images);
                if (!sources.contains(card.image)) sources.add(card.image);
                Exception lastFailure = null;
                for (String source : sources) {
                    try {
                        download(source, image);
                        ready = true;
                        break;
                    } catch (Exception failedSource) {
                        lastFailure = failedSource;
                    }
                }
                if (!ready && lastFailure != null) throw lastFailure;
            }
            if (!isLargeImage(image)) throw new java.io.IOException("artwork is below " + MIN_IMAGE_WIDTH + " px");
            Map<String, String> extras = new LinkedHashMap<>();
            extras.put("source", SOURCE);
            Library.Entry entry = new Library.Entry(image.getName(), card.title, PACKAGE,
                    "https://www.netflix.com/title/" + card.id, extras, detail.description);
            entry.file = image;
            entry.appLabel = appLabel;
            return entry;
        } catch (Exception failure) {
            Log.w(TAG, "Title artwork failed for Netflix title id " + card.id, failure);
            return null;
        }
    }

    private static final class Card {
        final String title, image, id;
        final int rank;
        Card(String title, String image, String id, int rank) {
            this.title = title; this.image = image; this.id = id; this.rank = rank;
        }
    }

    private static final class Detail {
        final List<String> images = new ArrayList<>();
        String description = "";
    }

    /** Tudum renders its ranked image cards and Netflix title links in the same order. */
    private static List<Card> parse(String html) {
        List<String[]> images = new ArrayList<>();
        Matcher imageTags = IMAGE.matcher(html);
        while (imageTags.find()) {
            Map<String, String> a = attrs(imageTags.group(1));
            String title = decode(a.get("alt"));
            String src = largestSource(a);
            if (title.isEmpty() || src == null || !src.contains("nflximg.net")) continue;
            if (title.regionMatches(true, 0, "Image: ", 0, 7)) title = title.substring(7).trim();
            if (title.equalsIgnoreCase("image") || title.length() > 140) continue;
            images.add(new String[] {title, absolute(src)});
        }
        Matcher ids = WATCH.matcher(html);
        List<String> titleIds = new ArrayList<>();
        // Keep repeated ranked links: Netflix may repeat one show id for separate seasons.
        while (ids.find()) titleIds.add(ids.group(1));
        int count = Math.min(10, images.size());
        List<Card> out = new ArrayList<>();
        Map<String, String> seriesIds = new HashMap<>();
        Set<String> seenShows = new HashSet<>(), seenIds = new HashSet<>();
        int nextId = 0;
        for (int i = 0; i < count; i++) {
            String title = images.get(i)[0];
            String series = seriesKey(title);
            String id = titleIds.size() >= count ? titleIds.get(i) : seriesIds.get(series);
            if (id == null && nextId < titleIds.size()) id = titleIds.get(nextId++);
            if (id == null) continue;
            seriesIds.put(series, id);
            if (!seenShows.add(series) || !seenIds.add(id)) continue;
            out.add(new Card(title, images.get(i)[1], id, i + 1));
        }
        Log.i(TAG, "Parsed " + out.size() + " ranked cards from " + images.size()
                + " poster tags and " + titleIds.size() + " title links");
        return out;
    }

    /** Netflix ranks seasons separately, although those entries can share one title link. */
    private static String seriesKey(String title) {
        String key = title.toLowerCase(Locale.ROOT).trim();
        key = key.replaceFirst("(?i)[:\\s-]+(?:season|series|part|volume|vol\\.?|s)\\s*#?\\s*\\d+.*$", "");
        key = key.replaceAll("[^a-z0-9]+", " ").trim();
        return key;
    }

    private static Detail parseDetail(String html) {
        Detail detail = new Detail();
        Matcher tags = META.matcher(html);
        while (tags.find()) {
            Map<String, String> a = attrs(tags.group(1));
            String key = a.containsKey("property") ? a.get("property") : a.get("name");
            String content = decode(a.get("content"));
            if (key == null || content.isEmpty()) continue;
            key = key.toLowerCase(Locale.ROOT);
            if (key.equals("og:image") || key.equals("twitter:image")) {
                String image = absolute(content);
                if (!detail.images.contains(image)) detail.images.add(image);
            } else if (key.equals("og:description") || key.equals("twitter:description")
                    || key.equals("description")) {
                if (detail.description.isEmpty()) detail.description = content;
            }
        }
        Matcher imageTags = IMAGE.matcher(html);
        while (imageTags.find()) {
            String image = largestSource(attrs(imageTags.group(1)));
            if (image != null && image.contains("nflximg.net")) {
                image = absolute(image);
                if (!detail.images.contains(image)) detail.images.add(image);
            }
        }
        return detail;
    }

    private static String largestSource(Map<String, String> a) {
        String best = first(a.get("src"), a.get("data-src"), a.get("data-original"));
        int bestWidth = 0;
        Matcher m = SRCSET.matcher(a.getOrDefault("srcset", ""));
        while (m.find()) {
            int width = 0;
            try { if (m.group(2) != null) width = Integer.parseInt(m.group(2)); }
            catch (NumberFormatException ignored) { }
            if (width >= bestWidth) { best = m.group(1); bestWidth = width; }
        }
        return best;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return null;
    }

    private static Map<String, String> attrs(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(text);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            out.put(m.group(1).toLowerCase(Locale.ROOT), value);
        }
        return out;
    }

    private static String get(String address, int max) throws Exception {
        HttpURLConnection c = open(address);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return new String(read(c.getInputStream(), max), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; Fire TV) AppleWebKit/537.36 Chrome/120 Safari/537.36");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
        return c;
    }

    private static void download(String address, File out) throws Exception {
        HttpURLConnection c = open(address);
        byte[] bytes;
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("art HTTP " + c.getResponseCode());
            bytes = read(c.getInputStream(), MAX_IMAGE_BYTES);
        } finally { c.disconnect(); }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (!isBannerSize(bounds.outWidth, bounds.outHeight))
            throw new java.io.IOException("small or invalid art: " + bounds.outWidth + "x" + bounds.outHeight);
        File temp = new File(out.getPath() + ".tmp");
        try (FileOutputStream f = new FileOutputStream(temp)) { f.write(bytes); }
        if (!temp.renameTo(out)) { temp.delete(); throw new java.io.IOException("cache write failed"); }
    }

    private static boolean isLargeImage(File image) {
        if (!image.isFile() || image.length() < 1024) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getPath(), bounds);
        return isBannerSize(bounds.outWidth, bounds.outHeight);
    }

    private static boolean isBannerSize(int width, int height) {
        float ratio = height <= 0 ? 0f : width / (float) height;
        return width >= MIN_IMAGE_WIDTH && height >= 560 && ratio >= 1.45f && ratio <= 2.25f;
    }

    private static byte[] read(InputStream in, int max) throws Exception {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = stream.read(buf)) != -1) {
                total += n; if (total > max) throw new java.io.IOException("response too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static List<Library.Entry> readCache(Context c, File dir, String slug) {
        List<Library.Entry> out = new ArrayList<>();
        File cache = new File(dir, "titles-" + slug + ".txt");
        if (!cache.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(
                new FileInputStream(cache), StandardCharsets.UTF_8))) {
            PackageManager pm = c.getPackageManager();
            CharSequence label = pm.getApplicationInfo(PACKAGE, 0).loadLabel(pm);
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\\t", -1); if (f.length != 4) continue;
                File image = new File(dir, f[0]); if (!isLargeImage(image)) continue;
                Map<String, String> extras = new LinkedHashMap<>(); extras.put("source", SOURCE);
                Library.Entry e = new Library.Entry(f[0], f[1], PACKAGE,
                        "https://www.netflix.com/title/" + f[2], extras, f[3]);
                e.file = image; e.appLabel = label; out.add(e);
            }
            out = uniqueShows(out);
        } catch (Exception ignored) { out.clear(); }
        return out;
    }

    private static List<Library.Entry> uniqueShows(List<Library.Entry> entries) {
        List<Library.Entry> unique = new ArrayList<>();
        Set<String> names = new HashSet<>(), ids = new HashSet<>();
        for (Library.Entry entry : entries) {
            String name = seriesKey(entry.title);
            String id = titleId(entry);
            if (name.isEmpty() || ids.contains(id) || names.contains(name)) continue;
            names.add(name);
            ids.add(id);
            unique.add(entry);
        }
        return unique;
    }

    private static void writeCache(File dir, String slug, List<Library.Entry> entries) {
        File temp = new File(dir, "titles-" + slug + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            for (Library.Entry e : entries) {
                String line = e.image + "\t" + safe(e.title) + "\t" + titleId(e)
                        + "\t" + safe(e.description) + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            File target = new File(dir, "titles-" + slug + ".txt");
            if (!temp.renameTo(target)) temp.delete();
        } catch (Exception ignored) { temp.delete(); }
    }

    private static String titleId(Library.Entry e) {
        return e.uri.substring(e.uri.lastIndexOf('/') + 1);
    }

    private static String safe(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String absolute(String src) {
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("/")) return "https://www.netflix.com" + src;
        return src;
    }

    private static String decode(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    private static String slug(String country) {
        String s = country == null ? "netherlands" : country.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) s = "netherlands";
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replace("&", "and").replace("'", "").replace("’", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (s.equals("united-states-of-america")) s = "united-states";
        if (s.equals("turkiye")) s = "turkey";
        if (s.equals("hong-kong-sar-china")) s = "hong-kong";
        return s;
    }
}
