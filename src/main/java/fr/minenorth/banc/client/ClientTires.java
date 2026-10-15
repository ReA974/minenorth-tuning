package fr.minenorth.banc.client;

import fr.minenorth.api.MtsTires;
import fr.minenorth.banc.FlatDrag;
import fr.minenorth.banc.MineNorthBanc;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Côté client : même correctif de définitions de roues que le serveur (hauteur « à plat » des pneus GVP, sinon une roue crevée s'affiche à la
 * mauvaise hauteur), et même bridage des véhicules aux pneus crevés (MTS simule aussi la voiture chez le joueur).
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
public final class ClientTires {
    private ClientTires() {}

    private static int counter;

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) { MtsTires.patchDefinitions(); }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (++counter >= 10) {
            counter = 0;
            for (Entity v : mc.level.entitiesForRendering()) if (MtsTires.isVehicle(v)) FlatDrag.refresh(v);
        }
        FlatDrag.tick();
    }
}
