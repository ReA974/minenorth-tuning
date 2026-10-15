package fr.minenorth.banc.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.minenorth.banc.MineNorthBanc;
import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Touche d'injection de nitro (Maj gauche par défaut, réassignable dans les contrôles) : maintenue au volant d'un véhicule équipé. */
public final class NitroKeys {
    private NitroKeys() {}

    public static final KeyMapping NITRO = new KeyMapping("key.minenorthbanc.nitro", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, "key.categories.minenorth");

    private static boolean sent;

    @Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Register {
        private Register() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) { e.register(NITRO); }
    }

    @Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
    public static final class Tick {
        private Tick() {}

        @SubscribeEvent
        public static void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            boolean down = mc.player != null && mc.player.getVehicle() != null && mc.screen == null && NITRO.isDown();
            if (down != sent) {
                sent = down;
                BancNetwork.CHANNEL.sendToServer(new BancNetwork.NitroKey(down));
            }
        }
    }
}
