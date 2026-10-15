package fr.minenorth.banc.client;

import fr.minenorth.banc.MineNorthBanc;
import fr.minenorth.banc.NitroService;
import fr.minenorth.banc.TuneAccess;
import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applique côté client le réglage reçu du serveur : MTS simule aussi la voiture chez le joueur, il lui faut les mêmes valeurs que le serveur
 * (sinon la voiture saccade). L'entité peut ne pas être encore prête à la réception : on réessaie toutes les secondes pendant quelques secondes,
 * puis on réapplique régulièrement (idempotent).
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID, value = Dist.CLIENT)
public final class ClientTune {
    private ClientTune() {}

    private static final Map<Integer, BancNetwork.TuneSync> STATE = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> MISSING = new ConcurrentHashMap<>();
    private static int counter;

    public static void receive(BancNetwork.TuneSync s) {
        STATE.put(s.entityId(), s);
        MISSING.remove(s.entityId());
        NitroService.setClientActive(s.entityId(), s.nitroOn());
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity e = mc.level.getEntity(s.entityId());
            if (e != null) apply(e, s);
        }
    }

    private static void apply(Entity e, BancNetwork.TuneSync s) {
        TuneAccess.Setup setup = new TuneAccess.Setup(s.stage(), 0, s.axle(), s.tires(), s.brakes(), s.nitro());
        setup.write(e);
        TuneAccess.applyTune(e, setup);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || STATE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { STATE.clear(); MISSING.clear(); return; }
        // poussée directe de la nitro, à chaque tick (même calcul que le serveur : la voiture est aussi simulée chez le joueur)
        for (BancNetwork.TuneSync s : STATE.values()) {
            if (!s.nitroOn()) continue;
            Entity v = mc.level.getEntity(s.entityId());
            if (v != null && !v.isRemoved()) TuneAccess.push(v, TuneAccess.NITRO_ACCEL[Math.max(0, Math.min(3, s.nitro()))]);
        }
        if (++counter < 20) return;
        counter = 0;
        for (Iterator<Map.Entry<Integer, BancNetwork.TuneSync>> it = STATE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, BancNetwork.TuneSync> en = it.next();
            Entity v = mc.level.getEntity(en.getKey());
            if (v == null || v.isRemoved()) {
                if (MISSING.merge(en.getKey(), 1, Integer::sum) > 30) { it.remove(); MISSING.remove(en.getKey()); NitroService.setClientActive(en.getKey(), false); }
                continue;
            }
            MISSING.remove(en.getKey());
            apply(v, en.getValue());
        }
    }
}
