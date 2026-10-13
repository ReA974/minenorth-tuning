package fr.minenorth.banc;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintient la reprogrammation des véhicules chargés : MTS recrée ses variables moteur aux valeurs d'usine quand un véhicule est
 * (re)chargé ; on les reporte toutes les 5 s. L'application est idempotente (valeurs d'usine x facteur du niveau).
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class TuneService {
    private TuneService() {}

    private static final Set<Entity> TUNED = ConcurrentHashMap.newKeySet();
    private static final int INTERVAL = 100;
    private static int counter;

    static void track(Entity e) { if (TuneAccess.stageOf(e) > 0) TUNED.add(e); else TUNED.remove(e); }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !TuneAccess.isVehicle(e.getEntity())) return;
        track(e.getEntity());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || TUNED.isEmpty() || ++counter < INTERVAL) return;
        counter = 0;
        for (Iterator<Entity> it = TUNED.iterator(); it.hasNext(); ) {
            Entity v = it.next();
            if (v.isRemoved()) { it.remove(); continue; }
            int stage = TuneAccess.stageOf(v);
            if (stage <= 0) { it.remove(); continue; }
            TuneAccess.applyTune(v, stage);
        }
    }
}
