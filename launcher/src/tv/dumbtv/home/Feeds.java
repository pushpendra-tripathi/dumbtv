package tv.dumbtv.home;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The three live feeds: weather (Open-Meteo, no key), the next F1 race (Jolpica, no key) and
 * new releases on the installed streaming apps (TMDB, the owner's key). Each is fetched on a
 * background thread only while its Options switch is on, cached on disk, and read from the
 * cache when the network fails. Nothing here touches views.
 */
final class Feeds {
    /** What to fetch, read from Config on the UI thread and handed to {@link #refresh}. */
    static final class Want {
        final boolean weather, f1, releases;
        final String city, key;
        final List<String> installed;

        Want(boolean weather, String city, boolean f1, boolean releases, String key, List<String> installed) {
            this.weather = weather;
            this.city = city;
            this.f1 = f1;
            this.releases = releases;
            this.key = key;
            this.installed = installed;
        }
    }

    static final class Session {
        final String name;
        final long at;

        Session(String name, long at) {
            this.name = name;
            this.at = at;
        }
    }

    static final class Race {
        final String name, round, place;
        /** The circuit's position, from Jolpica; used to find its track. */
        final double lat, lon;
        final long at;
        final List<Session> sessions;
        /** The track's outline from f1-circuits, as longitude and latitude per point, or null. */
        final double[] trackLon, trackLat;
        /** The f1-circuits id of that track, such as "us-2023", or null. */
        final String trackId;

        Race(String name, String round, String place, double lat, double lon, long at, List<Session> sessions,
                String trackId, double[] trackLon, double[] trackLat) {
            this.name = name;
            this.round = round;
            this.place = place;
            this.lat = lat;
            this.lon = lon;
            this.at = at;
            this.sessions = sessions;
            this.trackId = trackId;
            this.trackLon = trackLon;
            this.trackLat = trackLat;
        }

        Race withTrack(String id, double[] lon, double[] lat) {
            return new Race(name, round, place, this.lat, this.lon, at, sessions, id, lon, lat);
        }
    }

    static final class Slide {
        final String pkg, service, title;
        final boolean series;
        final File image;

        Slide(String pkg, String service, String title, boolean series, File image) {
            this.pkg = pkg;
            this.service = service;
            this.title = title;
            this.series = series;
            this.image = image;
        }
    }

    private static final long WEATHER_AGE = 30 * Info.MINUTE;
    private static final long RACE_AGE = 6 * Info.HOUR;
    private static final long RELEASES_AGE = 6 * Info.HOUR;
    private static final long PROVIDERS_AGE = 7 * Info.DAY;
    /** After a failed request, the same URL is not asked again for this long. */
    private static final long BACKOFF = 10 * Info.MINUTE;
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int PER_APP = 5;
    private static final String TMDB = "https://api.themoviedb.org/3/";
    private static final String[][] SESSIONS = {
        {"FirstPractice", "FP1"}, {"SecondPractice", "FP2"}, {"ThirdPractice", "FP3"},
        {"SprintQualifying", "Sprint Quali"}, {"SprintShootout", "Sprint Quali"},
        {"Sprint", "Sprint"}, {"Qualifying", "Quali"},
    };

    volatile String weather;
    /** The same weather for the Cinema 2 status corner: degrees and WMO code, or NaN and -1. */
    volatile double weatherTemp = Double.NaN;
    volatile int weatherCode = -1;
    volatile Race race;
    volatile Map<String, List<Slide>> slides = Collections.emptyMap();

    private final File dir;
    private final Map<String, Long> failed = new HashMap<>();

    Feeds(File base) {
        dir = new File(base, "feeds");
    }

    List<Slide> slidesFor(String pkg) {
        List<Slide> list = slides.get(pkg);
        return list == null ? Collections.<Slide>emptyList() : list;
    }

    /** Fetches whatever is stale. Background thread only. True when anything shown changed. */
    boolean refresh(Want w) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        boolean changed = false;

        String nextWeather = w.weather ? weather(w.city) : null;
        if (!same(nextWeather, weather)) {
            weather = nextWeather;
            changed = true;
        }

        Race nextRace = w.f1 ? race() : null;
        if (!sameRace(nextRace, race)) {
            race = nextRace;
            changed = true;
        }

        Map<String, List<Slide>> nextSlides = w.releases && !w.key.isEmpty()
                ? releases(w.key, w.installed) : Collections.<String, List<Slide>>emptyMap();
        if (!sameSlides(nextSlides, slides)) {
            slides = nextSlides;
            changed = true;
        }
        return changed;
    }

    // ---- weather ----

    private String weather(String city) {
        try {
            String name = city.trim().isEmpty() ? "Pune" : city.trim();
            JSONObject geo = json("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(name, "UTF-8"), "geo-" + slug(name) + ".json", Long.MAX_VALUE, null);
            JSONArray places = geo == null ? null : geo.optJSONArray("results");
            if (places == null || places.length() == 0) return null;
            JSONObject place = places.getJSONObject(0);
            String at = "latitude=" + place.getDouble("latitude") + "&longitude=" + place.getDouble("longitude");
            JSONObject now = json("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&" + at,
                    "weather-" + slug(name) + ".json", WEATHER_AGE, null);
            JSONObject current = now == null ? null : now.optJSONObject("current");
            if (current == null) return null;
            weatherTemp = current.getDouble("temperature_2m");
            weatherCode = current.getInt("weather_code");
            return Info.weather(current.getDouble("temperature_2m"), current.getInt("weather_code"),
                    place.optString("name", name));
        } catch (IOException | JSONException e) {
            return weather;
        }
    }

    // ---- F1 ----

    private Race race() {
        String url = "https://api.jolpi.ca/ergast/f1/current/next.json";
        Race r = parseRace(json(url, "f1-next.json", RACE_AGE, null));
        // A cached race that has finished: ask for the one after it now, not in six hours.
        if (r != null && r.at + Info.RACE_LENGTH < System.currentTimeMillis()) {
            r = parseRace(json(url, "f1-next.json", 0, null));
            if (r != null && r.at + Info.RACE_LENGTH < System.currentTimeMillis()) r = null;
        }
        return r == null ? null : track(r);
    }

    /**
     * {@code r} with its track outline: the f1-circuits track (MIT, github.com/bacinger/f1-circuits)
     * whose centre lies nearest the circuit Jolpica names, within 20 km. The file covers every
     * circuit and is fetched at most monthly.
     */
    private Race track(Race r) {
        dropLegacyMaps();
        JSONObject all = json("https://raw.githubusercontent.com/bacinger/f1-circuits/master/f1-circuits.geojson",
                "f1-circuits.geojson", 30 * Info.DAY, null);
        JSONArray features = all == null ? null : all.optJSONArray("features");
        if (features == null || Double.isNaN(r.lat)) return r;
        int n = features.length();
        double[] cLat = new double[n], cLon = new double[n];
        JSONArray[] paths = new JSONArray[n];
        for (int i = 0; i < n; i++) {
            JSONObject f = features.optJSONObject(i);
            JSONObject g = f == null ? null : f.optJSONObject("geometry");
            paths[i] = g == null ? null : g.optJSONArray("coordinates");
            cLat[i] = cLon[i] = Double.NaN;
            if (paths[i] == null || paths[i].length() == 0) continue;
            double sLat = 0, sLon = 0;
            for (int k = 0; k < paths[i].length(); k++) {
                JSONArray pt = paths[i].optJSONArray(k);
                sLon += pt.optDouble(0);
                sLat += pt.optDouble(1);
            }
            cLat[i] = sLat / paths[i].length();
            cLon[i] = sLon / paths[i].length();
        }
        int best = Info.nearest(r.lat, r.lon, cLat, cLon, 20);
        if (best < 0) return r;
        JSONArray path = paths[best];
        double[] lon = new double[path.length()], lat = new double[path.length()];
        for (int k = 0; k < path.length(); k++) {
            lon[k] = path.optJSONArray(k).optDouble(0);
            lat[k] = path.optJSONArray(k).optDouble(1);
        }
        JSONObject props = features.optJSONObject(best).optJSONObject("properties");
        return r.withTrack(props == null ? String.valueOf(best) : props.optString("id", String.valueOf(best)), lon, lat);
    }

    /** Wikipedia track maps from an earlier build: replaced by the outline, so not kept. */
    private void dropLegacyMaps() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("circuit-") || n.startsWith("wiki-")) f.delete();
        }
    }

    private static Race parseRace(JSONObject o) {
        if (o == null) return null;
        try {
            JSONArray races = o.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
            if (races.length() == 0) return null; // the season is over
            JSONObject r = races.getJSONObject(0);
            long at = instant(r);
            if (at <= 0) return null;
            List<Session> sessions = new ArrayList<>();
            for (String[] s : SESSIONS) {
                JSONObject part = r.optJSONObject(s[0]);
                long t = part == null ? 0 : instant(part);
                if (t > 0) sessions.add(new Session(s[1], t));
            }
            sessions.add(new Session("Race", at));
            Collections.sort(sessions, (a, b) -> Long.compare(a.at, b.at));
            JSONObject circuit = r.optJSONObject("Circuit");
            JSONObject where = circuit == null ? null : circuit.optJSONObject("Location");
            String place = where == null ? "" : where.optString("locality", "") + ", " + where.optString("country", "");
            double lat = where == null ? Double.NaN : where.optDouble("lat", Double.NaN);
            double lon = where == null ? Double.NaN : where.optDouble("long", Double.NaN);
            return new Race(r.getString("raceName"), r.optString("round", ""), place, lat, lon, at,
                    Collections.unmodifiableList(sessions), null, null, null);
        } catch (JSONException e) {
            return null;
        }
    }

    /** "date": "2026-11-08", "time": "17:00:00Z" as milliseconds, or 0. */
    private static long instant(JSONObject o) {
        String date = o.optString("date", ""), time = o.optString("time", "12:00:00Z");
        if (date.isEmpty()) return 0;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = f.parse(date + "T" + time.replace("Z", ""));
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    // ---- new releases ----

    private Map<String, List<Slide>> releases(String key, List<String> installed) {
        Map<String, String> auth = new HashMap<>();
        // A v4 read token is a long JWT and goes in a header; a v3 key is 32 hex characters.
        String keyParam = "";
        if (key.length() > 40) auth.put("Authorization", "Bearer " + key);
        else keyParam = "&api_key=" + key;

        JSONObject providers = json(TMDB + "watch/providers/movie?watch_region=IN" + keyParam,
                "tmdb-providers.json", PROVIDERS_AGE, auth);
        JSONArray list = providers == null ? null : providers.optJSONArray("results");
        if (list == null) return slides;

        Map<String, List<Slide>> out = new HashMap<>();
        Set<File> keep = new HashSet<>();
        long today = System.currentTimeMillis();
        // New films: released in the last 120 days. New series: premiered in the last 180, so a
        // long-running show with a fresh episode (the most popular by far) does not crowd them out.
        String to = day(today), movieFrom = day(today - 120 * Info.DAY), tvFrom = day(today - 180 * Info.DAY);
        // A title streaming on two installed services shows on the first of them only.
        Set<String> shown = new HashSet<>();
        for (String pkg : installed) {
            String service = Info.service(pkg);
            if (service == null) continue;
            int id = -1;
            for (int i = 0; i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p != null && Info.provides(p.optString("provider_name"), pkg)) {
                    id = p.optInt("provider_id", -1);
                    break;
                }
            }
            if (id < 0) continue;
            String common = "?watch_region=IN&with_watch_monetization_types=flatrate&sort_by=popularity.desc"
                    + "&with_watch_providers=" + id + keyParam;
            List<JSONObject> found = new ArrayList<>();
            collect(found, json(TMDB + "discover/movie" + common + "&primary_release_date.gte=" + movieFrom
                    + "&primary_release_date.lte=" + to, "tmdb-" + id + "-movie.json", RELEASES_AGE, auth), false);
            collect(found, json(TMDB + "discover/tv" + common + "&first_air_date.gte=" + tvFrom
                    + "&first_air_date.lte=" + to, "tmdb-" + id + "-tvnew.json", RELEASES_AGE, auth), true);
            Collections.sort(found, (a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
            List<Slide> mine = new ArrayList<>();
            for (JSONObject r : found) {
                if (mine.size() >= PER_APP) break;
                String path = r.optString("backdrop_path", "");
                if (path.isEmpty() || "null".equals(path)) continue;
                if (!shown.add((r.optBoolean("_series") ? "tv:" : "movie:") + r.optInt("id"))) continue;
                File image = new File(dir, "img-" + slug(path) + ".jpg");
                if (!image.isFile() && !download("https://image.tmdb.org/t/p/w780" + path, image)) continue;
                keep.add(image);
                boolean series = r.optBoolean("_series");
                mine.add(new Slide(pkg, service, r.optString(series ? "name" : "title"), series, image));
            }
            if (!mine.isEmpty()) out.put(pkg, Collections.unmodifiableList(mine));
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("img-") && !keep.contains(f)) f.delete();
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static void collect(List<JSONObject> into, JSONObject page, boolean series) {
        JSONArray results = page == null ? null : page.optJSONArray("results");
        if (results == null) return;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            try {
                r.put("_series", series);
            } catch (JSONException ignored) {
                continue;
            }
            into.add(r);
        }
    }

    private static String day(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        return f.format(new Date(ms));
    }

    // ---- HTTP and the disk cache ----

    /**
     * The JSON at {@code url}: from the cache file while it is younger than {@code maxAge},
     * otherwise fetched; on a failed fetch the stale cache, or null.
     */
    private JSONObject json(String url, String cacheName, long maxAge, Map<String, String> headers) {
        File cache = new File(dir, cacheName);
        long age = System.currentTimeMillis() - cache.lastModified();
        if (!cache.isFile() || age > maxAge) {
            byte[] body = get(url, headers);
            if (body != null) write(cache, body);
        }
        if (!cache.isFile()) return null;
        try {
            return new JSONObject(new String(read(cache), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private boolean download(String url, File to) {
        byte[] body = get(url, null);
        return body != null && write(to, body);
    }

    /** The body of a 200 response, or null. Never logs the URL: it can carry the TMDB key. */
    private byte[] get(String url, Map<String, String> headers) {
        Long last = failed.get(url);
        if (last != null && System.currentTimeMillis() - last < BACKOFF) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            // Wikimedia asks API clients to name themselves; no personal contact is sent.
            c.setRequestProperty("User-Agent", "dumbtv-home (https://github.com/humblemuzzu/dumbtv)");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("too large");
                }
                failed.remove(url);
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            failed.put(url, System.currentTimeMillis());
            // The host only: the query string can carry the TMDB key.
            Log.w("dumbtv-feeds", host(url) + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException e) {
            return "?";
        }
    }

    private static boolean write(File f, byte[] body) {
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean sameRace(Race a, Race b) {
        if (a == null || b == null) return a == b;
        return a.name.equals(b.name) && a.at == b.at && a.sessions.size() == b.sessions.size()
                && (a.trackId == null ? b.trackId == null : a.trackId.equals(b.trackId));
    }

    private static boolean sameSlides(Map<String, List<Slide>> a, Map<String, List<Slide>> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, List<Slide>> e : a.entrySet()) {
            List<Slide> x = e.getValue(), y = b.get(e.getKey());
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!x.get(i).image.equals(y.get(i).image)) return false;
            }
        }
        return true;
    }
}
