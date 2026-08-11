package com.ckemere.cubeworld.city;

import com.ckemere.cubeworld.teleport.TeleportService;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single reader of {@code cities_anchor.csv} — the one declarative source
 * for the special cities. Row format: {@code x,z,pool,size,name[# comment]}.
 *
 * <p>Every consumer (structure registration/anchoring, teleport station
 * seeding, terrain density relax, anchor-nudge tooling) reads the SAME parsed
 * list, so column semantics — in particular the name → registry-slug rule,
 * which is player-visible via {@code /locate structure cubeworld:<slug>} —
 * cannot drift between copies. Loaded once per JVM; the CSV is a bundled
 * resource and cannot change at runtime.
 */
public final class CityAnchors {

    /** One city: block coords, style pool (plains|desert|savanna), size tier
     * (large|huge), display name, and the de-collided registry slug. */
    public record CityAnchor(int x, int z, String pool, String size, String name, String slug) {
    }

    private static final String RESOURCE = "cities_anchor.csv";

    private static volatile List<CityAnchor> cached;

    private CityAnchors() {
    }

    /** The parsed city list (cached; never null, possibly empty). */
    public static List<CityAnchor> load() {
        List<CityAnchor> list = cached;
        if (list == null) {
            cached = list = parse();
        }
        return list;
    }

    private static List<CityAnchor> parse() {
        List<CityAnchor> out = new ArrayList<>();
        Set<String> slugs = new HashSet<>();
        try (InputStream in = CityAnchors.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split(",");
                    if (p.length < 5) {
                        continue;
                    }
                    String name = TeleportService.cityNameOf(p[4]);
                    String slug = name.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                    if (slug.isEmpty()) {
                        slug = "city";
                    }
                    String base = slug;
                    for (int n = 2; !slugs.add(slug); n++) {
                        slug = base + "_" + n;
                    }
                    out.add(new CityAnchor(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                            p[2].trim().toLowerCase(Locale.ROOT),
                            p[3].trim().toLowerCase(Locale.ROOT), name, slug));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return List.copyOf(out);
    }
}
