package fr.minenorth.banc;

import com.mojang.logging.LogUtils;
import fr.minenorth.api.MtsTires;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pneus crevables par balle. Deux choses :
 * 1. les pneus GVP n'avaient pas de hauteur « à plat » dans leur JSON, donc MTS ne les crevait jamais : on leur en donne une au démarrage ;
 * 2. les balles TACZ touchent le véhicule entier, jamais une roue : on suit chaque balle et, si sa trajectoire passe près d'une roue, la roue crève.
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class TireService {
    private TireService() {}

    /** Probabilité de crever quand une balle passe dans le pneu, et marge autour du pneu (blocs). */
    private static final double CHANCE = 0.9, MARGIN = 0.2;

    /** Balles suivies : {dernière position, dernier déplacement}. */
    private static final Map<Entity, Vec3[]> BULLETS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        LogUtils.getLogger().info("[Banc] Pneus rendus crevables : {} définitions de roues modifiées", MtsTires.patchDefinitions());
    }

    private static boolean isBullet(Entity e) {
        ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return k != null && k.getNamespace().equals("tacz") && k.getPath().contains("bullet");
    }

    /** Véhicules chargés, pour recompter leurs roues crevées (voir FlatDrag). */
    private static final java.util.Set<Entity> VEHICLES = ConcurrentHashMap.newKeySet();
    private static int counter;

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        if (isBullet(e.getEntity())) BULLETS.put(e.getEntity(), new Vec3[] {e.getEntity().position(), e.getEntity().getDeltaMovement()});
        else if (MtsTires.isVehicle(e.getEntity())) VEHICLES.add(e.getEntity());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++counter >= 10) {
            counter = 0;
            VEHICLES.removeIf(Entity::isRemoved);
            for (Entity v : VEHICLES) FlatDrag.refresh(v);
        }
        FlatDrag.tick();
        if (BULLETS.isEmpty()) return;
        for (var it = BULLETS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Vec3[]> en = it.next();
            Entity b = en.getKey();
            Vec3 start = en.getValue()[0], pos = b.position(), motion = en.getValue()[1];
            if (b.level() instanceof ServerLevel level) {
                test(level, start, pos);
                // balle déjà détruite sur le véhicule : elle a parcouru son dernier déplacement jusqu'à l'impact
                if (b.isRemoved() && pos.distanceToSqr(start) < 1e-4) test(level, start, start.add(motion));
            }
            if (b.isRemoved()) { it.remove(); continue; }
            en.setValue(new Vec3[] {pos, pos.subtract(start).lengthSqr() > 1e-6 ? pos.subtract(start) : b.getDeltaMovement()});
        }
    }

    private static void test(ServerLevel level, Vec3 a, Vec3 b) {
        AABB box = new AABB(a, b).inflate(3.5);
        List<Entity> vehicles = new ArrayList<>(level.getEntities((Entity) null, box, MtsTires::isVehicle));
        Object best = null;
        double bd = Double.MAX_VALUE;
        for (Entity v : vehicles) {
            for (Object w : MtsTires.wheels(v)) {
                double[] p = MtsTires.position(w);
                if (p == null || Double.isNaN(p[0]) || MtsTires.isFlat(w)) continue;
                double d = distance(new Vec3(p[0], p[1], p[2]), a, b), reach = MtsTires.radius(w) + MARGIN;
                if (d <= reach && d < bd) { bd = d; best = w; }
            }
        }
        if (best != null && level.random.nextDouble() < CHANCE) MtsTires.puncture(best);
    }

    /** Distance d'un point au segment [a, b]. */
    private static double distance(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double l = ab.lengthSqr();
        double t = l < 1e-9 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / l));
        return p.distanceTo(a.add(ab.scale(t)));
    }
}
