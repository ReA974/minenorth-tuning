package fr.minenorth.banc;

import fr.minenorth.api.MtsTires;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un véhicule aux pneus crevés (pneus de packs GVP, qui roulaient presque normalement) est bridé : vitesse maximale selon le nombre de roues
 * crevées. Calculé à l'identique côté serveur et côté client (MTS simule la voiture aux deux endroits, sinon elle saccade).
 * Le nombre de roues crevées est relu toutes les 10 ticks, la limite est appliquée à chaque tick.
 */
public final class FlatDrag {
    private FlatDrag() {}

    /** Vitesse maximale (blocs/tick, 1 = 72 km/h) avec 1, 2, 3 roues crevées ou plus : ~32, ~21, ~11 km/h. */
    private static final double[] CAP = {Double.MAX_VALUE, 0.45, 0.30, 0.15};

    private static final Map<Entity, Integer> FLAT = new ConcurrentHashMap<>();

    /** Recompte les roues crevées (pneus « patchés » seulement) du véhicule. */
    public static void refresh(Entity v) {
        int n = 0;
        for (Object w : MtsTires.wheels(v)) if (MtsTires.isFlat(w) && MtsTires.isPatched(w)) n++;
        if (n > 0) FLAT.put(v, n); else FLAT.remove(v);
    }

    /** Applique la limite de vitesse aux véhicules suivis (à chaque tick). */
    public static void tick() {
        for (var it = FLAT.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getKey().isRemoved()) { it.remove(); continue; }
            MtsTires.slowTo(en.getKey(), CAP[Math.min(3, en.getValue())]);
        }
    }
}
