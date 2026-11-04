package fr.minenorth.banc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sorties d'échappement imposées par modèle MTS (« pack:modele »), dans {@code config/minenorth-echappements.json}. Sert quand la définition
 * MTS du véhicule ne décrit pas ses échappements (pas de particules de fumée moteur) ou les place mal. Chaque sortie : [x, y, z] en blocs dans le
 * repère du modèle (x à droite, y en haut, z vers l'avant ; l'arrière est en z négatif), avec en option la direction du jet [dx, dy, dz]
 * (par défaut vers l'arrière). Côté serveur : la liste part à chaque joueur à la connexion et après /mnbanc echappements recharger.
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class ExhaustFits {
    private ExhaustFits() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, List<float[]>> FITS = new TreeMap<>();
    private static boolean loaded;

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("minenorth-echappements.json"); }

    public static synchronized Map<String, List<float[]>> all() {
        if (!loaded) load();
        return new TreeMap<>(FITS);
    }

    /** Relit le fichier ; renvoie le nombre de modèles réglés. */
    public static synchronized int load() {
        loaded = true;
        FITS.clear();
        Path f = file();
        if (!Files.exists(f)) { writeExample(f); return 0; }
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            JsonObject models = root == null || !root.has("modeles") ? new JsonObject() : root.getAsJsonObject("modeles");
            for (String k : models.keySet()) {
                if (k.startsWith("_") || !models.get(k).isJsonArray()) continue;
                List<float[]> pipes = new ArrayList<>();
                for (JsonElement el : models.getAsJsonArray(k)) {
                    if (!el.isJsonArray() || el.getAsJsonArray().size() < 3) continue;
                    JsonArray a = el.getAsJsonArray();
                    float[] p = {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), 0, 0, -1};
                    if (a.size() >= 6) { p[3] = a.get(3).getAsFloat(); p[4] = a.get(4).getAsFloat(); p[5] = a.get(5).getAsFloat(); }
                    if (pipes.size() < 6) pipes.add(p);
                }
                if (!pipes.isEmpty()) FITS.put(k, pipes);
            }
        } catch (Exception ex) {
            com.mojang.logging.LogUtils.getLogger().warn("[Banc] {} illisible : {}", f, ex.toString());
        }
        return FITS.size();
    }

    private static void writeExample(Path f) {
        JsonObject root = new JsonObject();
        root.addProperty("_aide", "Sorties d'échappement par modèle MTS (pack:modele, donné par /mnbanc echappements info), utilisées pour les flammes "
                + "de nitro à la place de celles lues dans le pack. Chaque sortie : [x, y, z] en blocs (x à droite, y en haut, z vers l'avant : "
                + "l'arrière est négatif), ou [x, y, z, dx, dy, dz] avec la direction du jet. Après modification : /mnbanc echappements recharger.");
        JsonObject models = new JsonObject();
        JsonArray ex = new JsonArray();
        ex.add(GSON.toJsonTree(new float[]{0.5f, 0.25f, -2.1f}));
        ex.add(GSON.toJsonTree(new float[]{-0.5f, 0.25f, -2.1f}));
        models.add("_exemple:modele", ex);
        root.add("modeles", models);
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { GSON.toJson(root, w); }
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("[Banc] Écriture de {} impossible : {}", f, e.toString());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) BancNetwork.exhaustsTo(p, all());
    }
}
