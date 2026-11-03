package fr.minenorth.banc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Corrections de la forme des néons par modèle MTS (« pack:modele », toutes variantes de peinture confondues), dans
 * {@code config/minenorth-neons.json}. La forme de base est calculée chez le joueur d'après le modèle 3D du véhicule (bas de caisse) ;
 * ces valeurs, en blocs, s'y ajoutent : largeur (des deux côtés), avant, arrière, hauteur. Réglées en jeu par le mécano (onglet Néons du banc)
 * ou à la main dans le fichier (puis /mnbanc neons recharger). Côté serveur seulement : les valeurs partent aux joueurs avec l'état des néons.
 */
public final class NeonFits {
    private NeonFits() {}

    public record Fit(float width, float front, float rear, float height) {
        public static final Fit NONE = new Fit(0, 0, 0, 0);
        public static final float MAX = 1.5f;

        public Fit clamp() {
            return new Fit(c(width), c(front), c(rear), c(height));
        }

        private static float c(float v) { return Math.round(Math.max(-MAX, Math.min(MAX, v)) * 100) / 100f; }

        public boolean isNone() { return equals(NONE); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Fit> FITS = new TreeMap<>();
    private static boolean loaded;

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("minenorth-neons.json"); }

    public static synchronized Fit get(String model) {
        if (!loaded) load();
        return model == null || model.isEmpty() ? Fit.NONE : FITS.getOrDefault(model, Fit.NONE);
    }

    public static synchronized java.util.Set<String> models() { return new java.util.TreeSet<>(FITS.keySet()); }

    /** Relit le fichier ; renvoie le nombre de modèles réglés. */
    public static synchronized int load() {
        loaded = true;
        FITS.clear();
        Path f = file();
        if (!Files.exists(f)) { save(); return 0; }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            JsonObject models = root == null || !root.has("modeles") ? new JsonObject() : root.getAsJsonObject("modeles");
            for (String k : models.keySet()) {
                JsonObject o = models.getAsJsonObject(k);
                FITS.put(k, new Fit(num(o, "largeur"), num(o, "avant"), num(o, "arriere"), num(o, "hauteur")).clamp());
            }
        } catch (Exception ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[Banc] {} illisible : {}", f, ex.toString());
        }
        return FITS.size();
    }

    private static float num(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsFloat() : 0f; }

    public static synchronized void put(String model, Fit fit) {
        if (!loaded) load();
        if (fit.isNone()) FITS.remove(model); else FITS.put(model, fit.clamp());
        save();
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("_aide", "Corrections des néons par modèle MTS (pack:modele), en blocs, ajoutées à la forme lue dans le modèle 3D : "
                + "largeur (des deux côtés), avant, arriere, hauteur (négatif = plus bas). Réglables en jeu au banc (onglet Néons) ; "
                + "après une modification à la main : /mnbanc neons recharger.");
        JsonObject models = new JsonObject();
        for (Map.Entry<String, Fit> en : FITS.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("largeur", en.getValue().width());
            o.addProperty("avant", en.getValue().front());
            o.addProperty("arriere", en.getValue().rear());
            o.addProperty("hauteur", en.getValue().height());
            models.add(en.getKey(), o);
        }
        root.add("modeles", models);
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) { GSON.toJson(root, w); }
        } catch (Exception ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[Banc] Écriture de {} impossible : {}", file(), ex.toString());
        }
    }
}
