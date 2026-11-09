package tv.dumbtv.home;

import java.util.Locale;

/**
 * The pure parts of the live feeds (weather, F1, new releases): wording, countdowns and which
 * streaming service an installed app is. No Android types, so it is tested on the host.
 */
final class Info {
    static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    /** How long after its start a race still counts as the next one: it may still be running. */
    static final long RACE_LENGTH = 3 * HOUR;

    private Info() {}

    /** WMO weather interpretation code, as Open-Meteo reports it, in two words at most. */
    static String sky(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95 && code <= 99) return "Thunderstorm";
        return "";
    }

    /** "26° Clear · Pune". */
    static String weather(double celsius, int code, String city) {
        String sky = sky(code);
        StringBuilder s = new StringBuilder().append(Math.round(celsius)).append('°');
        if (!sky.isEmpty()) s.append(' ').append(sky);
        if (city != null && !city.trim().isEmpty()) s.append(" · ").append(city.trim());
        return s.toString();
    }

    /** "2d 4h", "4h 12m", "12m"; "now" from the start until {@link #RACE_LENGTH} after it. */
    static String countdown(long now, long at) {
        long left = at - now;
        if (left <= 0) return "now";
        long d = left / DAY, h = left % DAY / HOUR, m = (left % HOUR + MINUTE - 1) / MINUTE;
        if (m == 60) {
            m = 0;
            h++;
        }
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    /** "ROUND 20  ·  SÃO PAULO, BRAZIL", or just the part that is known. */
    static String raceLine(String round, String place) {
        StringBuilder s = new StringBuilder();
        if (round != null && !round.isEmpty()) s.append("Round ").append(round);
        if (place != null && !place.replace(",", "").trim().isEmpty()) {
            if (s.length() > 0) s.append("  ·  ");
            s.append(place.trim());
        }
        return s.toString().toUpperCase(Locale.ROOT);
    }

    /** Great-circle distance in kilometres. */
    static double km(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371 * 2 * Math.asin(Math.sqrt(h));
    }

    /** The index of the point nearest ({@code lat}, {@code lon}) within {@code maxKm}, or -1. NaN points are skipped. */
    static int nearest(double lat, double lon, double[] lats, double[] lons, double maxKm) {
        int best = -1;
        double bestKm = maxKm;
        for (int i = 0; i < lats.length; i++) {
            if (Double.isNaN(lats[i]) || Double.isNaN(lons[i])) continue;
            double d = km(lat, lon, lats[i], lons[i]);
            if (d <= bestKm) {
                bestKm = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * A track outline in screen coordinates (x0, y0, x1, y1, ...): longitude scaled by the cosine
     * of the mean latitude so the shape is not stretched, north up, fitted inside the box with its
     * proportions kept, and centred in it.
     */
    static float[] fit(double[] lon, double[] lat, float left, float top, float width, float height) {
        int n = Math.min(lon.length, lat.length);
        float[] out = new float[n * 2];
        if (n == 0) return out;
        double meanLat = 0;
        for (int i = 0; i < n; i++) meanLat += lat[i];
        double k = Math.cos(Math.toRadians(meanLat / n));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = lon[i] * k, y = -lat[i];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        double w = Math.max(maxX - minX, 1e-9), h = Math.max(maxY - minY, 1e-9);
        double scale = Math.min(width / w, height / h);
        double offX = left + (width - w * scale) / 2, offY = top + (height - h * scale) / 2;
        for (int i = 0; i < n; i++) {
            out[i * 2] = (float) (offX + (lon[i] * k - minX) * scale);
            out[i * 2 + 1] = (float) (offY + (-lat[i] - minY) * scale);
        }
        return out;
    }

    /** The one app whose tile shows the F1 race card in the hero: FanCode, which streams F1. */
    static boolean showsF1(String pkg) {
        return "com.fancode.tv".equals(pkg);
    }

    /**
     * The service an installed app streams, as a person reads it, or null for any other app.
     * The names TMDB uses for the same services are matched by {@link #provides}.
     */
    static String service(String pkg) {
        if (pkg == null) return null;
        switch (pkg) {
            case "com.netflix.ninja":
                return "Netflix";
            case "com.amazon.firebat":
                return "Prime Video";
            case "in.startv.hotstar":
                return "JioHotstar";
            case "com.onemainstream.sonyliv.android":
                return "SonyLIV";
            case "com.zee5.amazon":
                return "ZEE5";
            case "com.discoveryplus.tv.fire":
                return "discovery+";
            default:
                return null;
        }
    }

    /** True when TMDB's provider {@code name} is the service installed as {@code pkg}. */
    static boolean provides(String name, String pkg) {
        String service = service(pkg);
        if (service == null || name == null) return false;
        String n = squash(name);
        switch (pkg) {
            case "com.amazon.firebat":
                // "Amazon Prime Video"; not "Amazon Video" (rent/buy) or "...with Ads" channels.
                return n.equals("amazonprimevideo") || n.equals("primevideo");
            case "in.startv.hotstar":
                return n.equals("jiohotstar") || n.equals("hotstar") || n.equals("disneyplushotstar");
            default:
                return n.equals(squash(service));
        }
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
