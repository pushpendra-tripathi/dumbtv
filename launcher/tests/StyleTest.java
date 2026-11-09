package tv.dumbtv.home;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Host-side checks for Options › Look, without a device or test framework. */
public final class StyleTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        check("classic".equals(Config.STYLE.fallback), "the 1.7 look stays the default");
        check(Arrays.asList(Config.STYLE.values).equals(Arrays.asList("classic", "cinema", "cinema2")),
                "three looks: Cinema 1 stays selectable next to Cinema 2");
        check("off".equals(Config.AMBIENT.fallback), "ambient mode only when turned on");
        check(Arrays.asList(Config.CHOICES).contains(Config.AMBIENT), "Ambient mode shows in Options");
        check(Arrays.asList(Config.CHOICES).contains(Config.STYLE), "Look shows in Options");

        // The README's home.conf sample and settings table name every look.
        String readme = new String(Files.readAllBytes(Paths.get(args.length > 0 ? args[0] : "launcher/README.md")),
                StandardCharsets.UTF_8);
        check(readme.contains("# " + String.join(" ", Config.STYLE.values) + "\n" + Config.STYLE.key + "="
                + Config.STYLE.fallback + "\n"), "README home.conf sample lists the looks");
        check(readme.contains("| " + Config.STYLE.title + " | "), "README settings table has Look");
        System.out.println("StyleTest passed");
    }
}
