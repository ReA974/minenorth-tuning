package fr.minenorth.banc.client;

import fr.minenorth.api.MtsTires;
import fr.minenorth.banc.MineNorthBanc;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Même correctif de définitions de roues que le serveur (hauteur « à plat » des pneus GVP), sinon une roue crevée s'affiche à la mauvaise hauteur. */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
public final class ClientTires {
    private ClientTires() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) { MtsTires.patchDefinitions(); }
}
