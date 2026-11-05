package tv.dumbtv.home;

import java.util.Locale;

/**
 * The pure parts of the live feeds (weather, F1, new releases): wording, countdowns and which
 * streaming service an installed app is. No Android types, so it is tested on the host.
 */
final class Info {
    static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    /** A race weekend shows in the hero this long before the race starts. */
    static final long RACE_WEEK = 7 * DAY;
    /** ...and keeps showing this long after it, while the race may still be running. */
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

    /** The app whose tile shows the F1 schedule all season, not only in race week: FanCode. */
    static boolean showsF1(String pkg) {
        return "com.fancode.tv".equals(pkg);
    }

    /** True while a race at {@code at} is close enough, or recent enough, to fill the hero. */
    static boolean raceWeek(long now, long at) {
        return at > 0 && now >= at - RACE_WEEK && now <= at + RACE_LENGTH;
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
