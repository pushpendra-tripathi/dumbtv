package tv.dumbtv.home;

import java.util.Arrays;

/** Host-side checks for the live feeds' wording and matching, without a device. */
public final class InfoTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check("Clear".equals(Info.sky(0)), "code 0 is clear");
        check("Partly cloudy".equals(Info.sky(2)), "code 2");
        check("Rain".equals(Info.sky(63)), "61-67 is rain");
        check("Thunderstorm".equals(Info.sky(95)), "95 is a thunderstorm");
        check("".equals(Info.sky(42)), "unknown codes say nothing");
        check("26° Clear · Pune".equals(Info.weather(26.4, 0, "Pune")), "weather line");
        check("-3° Snow".equals(Info.weather(-2.6, 73, " ")), "no city, rounded below zero");

        long d = Info.DAY, h = Info.HOUR, m = Info.MINUTE;
        check("2d 4h".equals(Info.countdown(0, 2 * d + 4 * h + 30 * m)), "days and hours");
        check("4h 12m".equals(Info.countdown(0, 4 * h + 12 * m)), "hours and minutes");
        check("1m".equals(Info.countdown(0, 20_000)), "under a minute rounds up");
        check("1h 0m".equals(Info.countdown(0, 59 * m + 30_000)), "59.5 minutes is an hour, not 60m");
        check("now".equals(Info.countdown(10, 10)), "at the start");

        check(Info.showsF1("com.fancode.tv"), "FanCode carries the schedule all season");
        // Interlagos as Jolpica places it, against two track centres from f1-circuits.
        double[] lats = {36.1147, -23.7019, Double.NaN}, lons = {-115.1706, -46.6985, 0};
        check(Info.nearest(-23.7036, -46.6997, lats, lons, 20) == 1, "nearest track is Interlagos");
        check(Info.nearest(51.5, -0.12, lats, lons, 20) == -1, "nothing within 20 km");
        check(Math.abs(Info.km(0, 0, 0, 1) - 111.19) < 0.1, "one degree of longitude at the equator");
        // A square track, 0.01 degrees a side, fitted into a wide box: square, centred, north up.
        float[] f = Info.fit(new double[] {0, 0.01, 0.01, 0}, new double[] {0.01, 0.01, 0, 0}, 0, 0, 200, 100);
        check(Math.abs((f[2] - f[0]) - (f[5] - f[3])) < 0.5f, "proportions kept");
        check(Math.abs(f[0] - 50) < 0.5f && Math.abs(f[1]) < 0.5f, "centred, north edge at the top");
        check(Math.abs(f[5] - 100) < 0.5f, "fills the box height");
        check("ROUND 20  ·  SÃO PAULO, BRAZIL".equals(Info.raceLine("20", "São Paulo, Brazil")), "race line");
        check("ROUND 20".equals(Info.raceLine("20", ", ")), "no place");
        check(!Info.showsF1("com.netflix.ninja"), "no other app shows the race card");

        check(Info.provides("Netflix", "com.netflix.ninja"), "Netflix");
        check(Info.provides("Amazon Prime Video", "com.amazon.firebat"), "Prime Video");
        check(!Info.provides("Amazon Video", "com.amazon.firebat"), "not the rent/buy store");
        check(!Info.provides("Amazon Prime Video with Ads", "com.amazon.firebat"), "not an ads channel");
        check(Info.provides("JioHotstar", "in.startv.hotstar"), "JioHotstar");
        check(Info.provides("Hotstar", "in.startv.hotstar"), "Hotstar, its older name");
        check(Info.provides("Sony Liv", "com.onemainstream.sonyliv.android"), "SonyLIV spacing");
        check(Info.provides("Zee5", "com.zee5.amazon"), "ZEE5 case");
        check(!Info.provides("Netflix", "org.smarttube.stable"), "not a streaming service");
        check(Info.provides("Discovery+", "com.discoveryplus.tv.fire"), "discovery+");
        check(Info.service("com.amazon.firetv.youtube") == null, "YouTube: TMDB lists rentals only");
        check(Info.service("com.fancode.tv") == null, "no TMDB provider for FanCode");

        check(Arrays.asList(Config.CHOICES).containsAll(Arrays.asList(
                Config.WEATHER, Config.F1, Config.RELEASES, Config.WIFI)), "feed switches show in Options");
        for (Config.Choice c : new Config.Choice[] {Config.WEATHER, Config.F1, Config.RELEASES, Config.WIFI}) {
            check("off".equals(c.fallback), c.key + " is off unless turned on: no network by default");
        }
        System.out.println("InfoTest passed");
    }
}
