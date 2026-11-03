package fr.minenorth.banc.client;

import fr.minenorth.banc.TuneAccess;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forme du bas de caisse d'un véhicule MTS, lue dans son modèle 3D (OBJ du pack, le même fichier que MTS affiche ; repère du modèle :
 * +z vers l'avant). Les boîtes de collision ne conviennent pas : beaucoup de packs (New Cars...) n'en déclarent que deux, sur le toit.
 * On prend le bas du modèle (1er centile des hauteurs, pour ignorer un échappement qui pend) et l'emprise des sommets situés dans les 35 cm
 * au-dessus : c'est le contour des bas de caisse, pare-chocs compris, sans les rétroviseurs. Lecture en tâche de fond, une fois par modèle.
 * Résultat : {minX, maxX, minZ, maxZ, bas}. Repli : d'après les roues (TuneAccess.wheelFootprint), puis une voiture moyenne.
 */
public final class NeonShapes {
    private NeonShapes() {}

    /** Voiture moyenne des packs installés (New Cars, Kaminari, Belroft : ~5,5 x 2,2 m, origine près de l'essieu arrière). */
    static final double[] DEFAULT = {-1.08, 1.08, -1.35, 4.1, -0.1};
    private static final double[] FAILED = new double[0];
    private static final Map<String, double[]> BY_MODEL = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<double[]>> LOADING = new ConcurrentHashMap<>();
    private static final Map<Integer, double[]> WHEELS = new ConcurrentHashMap<>();

    static void clear() { BY_MODEL.clear(); LOADING.clear(); WHEELS.clear(); }

    static void forget(int entityId) { WHEELS.remove(entityId); }

    /** Forme du véhicule (jamais null). */
    static double[] of(Entity v) {
        String loc = TuneAccess.modelLocation(v);
        if (loc != null) {
            double[] s = BY_MODEL.get(loc);
            if (s == null) {
                CompletableFuture<double[]> f = LOADING.computeIfAbsent(loc, l -> CompletableFuture.supplyAsync(() -> read(l), Util.backgroundExecutor()));
                if (f.isDone()) { s = f.getNow(FAILED); BY_MODEL.put(loc, s); LOADING.remove(loc); }
            }
            if (s != null && s.length == 5) return s;
        }
        double[] w = WHEELS.get(v.getId());
        if (w == null) {
            w = TuneAccess.wheelFootprint(v);
            if (w != null) WHEELS.put(v.getId(), w);
        }
        return w != null ? w : DEFAULT;
    }

    private static InputStream open(String loc) throws Exception {
        String path = loc.startsWith("/") ? loc.substring(1) : loc;
        if (path.startsWith("assets/")) {
            String rest = path.substring("assets/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                ResourceLocation rl = ResourceLocation.tryBuild(rest.substring(0, slash), rest.substring(slash + 1));
                if (rl != null) {
                    var res = Minecraft.getInstance().getResourceManager().getResource(rl);
                    if (res.isPresent()) return res.get().open();
                }
            }
        }
        // repli : chargeur de classes de MTS (les packs sont des mods)
        InputStream in = Class.forName("minecrafttransportsimulator.jsondefs.AJSONBase").getResourceAsStream(loc.startsWith("/") ? loc : "/" + loc);
        if (in == null) throw new IllegalStateException("introuvable");
        return in;
    }

    private static double[] read(String loc) {
        if (!loc.toLowerCase(java.util.Locale.ROOT).endsWith(".obj")) return FAILED;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(loc), StandardCharsets.UTF_8))) {
            double[] xs = new double[4096], ys = new double[4096], zs = new double[4096];
            int n = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() < 2 || line.charAt(0) != 'v' || line.charAt(1) != ' ') continue;
                String[] p = line.trim().split("\\s+");
                if (p.length < 4) continue;
                if (n == xs.length) { xs = Arrays.copyOf(xs, n * 2); ys = Arrays.copyOf(ys, n * 2); zs = Arrays.copyOf(zs, n * 2); }
                xs[n] = Double.parseDouble(p[1]); ys[n] = Double.parseDouble(p[2]); zs[n] = Double.parseDouble(p[3]);
                n++;
            }
            if (n < 8) return FAILED;
            double[] sorted = Arrays.copyOf(ys, n);
            Arrays.sort(sorted);
            double bottom = sorted[n / 100];
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (ys[i] < bottom || ys[i] > bottom + 0.35) continue;
                minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
                minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
            }
            if (maxX - minX < 0.3 || maxZ - minZ < 0.5) return FAILED;
            com.mojang.logging.LogUtils.getLogger().debug("[Banc] Néons : forme de {} = x[{}, {}] z[{}, {}] bas {}", loc, minX, maxX, minZ, maxZ, bottom);
            return new double[]{minX, maxX, minZ, maxZ, bottom};
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().info("[Banc] Néons : modèle {} illisible ({}), forme d'après les roues.", loc, t.toString());
            return FAILED;
        }
    }
}
