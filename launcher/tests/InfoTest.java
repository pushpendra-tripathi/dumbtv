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
        check("https://u/thumb/c/cf/x.svg/960px-x.svg.png?a=1".equals(
                Info.thumb("https://u/thumb/c/cf/x.svg/1920px-x.svg.png?a=1", 960)), "thumbnail resized");
        check("https://u/x.png".equals(Info.thumb("https://u/x.png", 960)), "a plain URL is left alone");
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
