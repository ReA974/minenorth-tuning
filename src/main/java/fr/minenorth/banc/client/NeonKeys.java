package fr.minenorth.banc.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.minenorth.banc.MineNorthBanc;
import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Touche des néons : un appui au volant = allumer / éteindre. J par défaut : libre dans les commandes voiture de MTS (N = frein à main) et
 * hors d'Oculus (O ouvre son écran des packs de shaders : la touche O de la première version n'arrivait jamais aux néons). Identifiant
 * « neons_caisse » nouveau pour que les joueurs qui avaient déjà O enregistré dans leurs options reçoivent le nouveau défaut.
 * La touche est lue sur l'événement clavier brut (et non par consumeClick) : si une autre commande a la même touche, Minecraft ne donne
 * l'appui qu'à une seule des deux ; ici les néons le reçoivent quand même. Réassignable dans Contrôles > MineNorth.
 */
public final class NeonKeys {
    private NeonKeys() {}

    public static final KeyMapping NEONS = new KeyMapping("key.minenorthbanc.neons_caisse", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.minenorth");

    private static long lastSent;

    @Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Register {
        private Register() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) { e.register(NEONS); }
    }

    @Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
    public static final class Input {
        private Input() {}

        @SubscribeEvent
        public static void onKey(InputEvent.Key e) {
            if (e.getAction() != GLFW.GLFW_PRESS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || mc.player == null || mc.getConnection() == null) return;   // le serveur vérifie le siège (MTS)
            if (NEONS.isUnbound() || !NEONS.getKey().equals(InputConstants.getKey(e.getKey(), e.getScanCode()))) return;
            long now = System.currentTimeMillis();
            if (now - lastSent < 250) return;
            lastSent = now;
            try {
                BancNetwork.CHANNEL.sendToServer(new BancNetwork.NeonKey());
            } catch (RuntimeException ignored) {}
        }
    }
}
