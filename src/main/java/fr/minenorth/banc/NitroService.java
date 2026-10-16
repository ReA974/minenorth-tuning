package fr.minenorth.banc;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nitro : kit installé au banc (niveau I / II / III), réserve de gaz en ticks rangée dans les données persistantes du véhicule, injection tant que
 * le conducteur maintient la touche (paquet client -> serveur). Pendant l'injection la puissance et l'adhérence augmentent (voir TuneAccess) et des
 * flammes colorées sortent des échappements (particules : orange / bleu / violet selon le niveau). Les bouteilles rechargent le véhicule
 * (clic droit sur le véhicule, ou clic accroupi sur un chariot de bouteilles à proximité).
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class NitroService {
    private NitroService() {}

    /** Une bouteille = 15 s d'injection ; la réserve d'un véhicule est limitée à 90 s. */
    public static final int BOTTLE_TICKS = 300, MAX_TICKS = 1800;
    /** Repli si la géométrie du véhicule est illisible : distance derrière l'origine, écart latéral, hauteur. */
    private static final double REAR = 1.3, SIDE = 0.45, HEIGHT = 0.3;
    /** Véhicule -> joueur qui maintient la touche. */
    private static final Map<Entity, UUID> ACTIVE = new ConcurrentHashMap<>();
    /** Ticks depuis le dernier départ du son de nitro (il dure 11,8 s : on le relance si l'injection continue). */
    private static final Map<Entity, Integer> SOUND_AGE = new ConcurrentHashMap<>();
    private static final int SOUND_TICKS = 230;

    /** Côté client : véhicules (par identifiant) dont le serveur dit que la nitro est en cours (voir ClientTune). */
    private static final java.util.Set<Integer> CLIENT_ACTIVE = ConcurrentHashMap.newKeySet();

    public static void setClientActive(int entityId, boolean on) { if (on) CLIENT_ACTIVE.add(entityId); else CLIENT_ACTIVE.remove(entityId); }

    public static boolean isActive(Entity e) { return e.level().isClientSide ? CLIENT_ACTIVE.contains(e.getId()) : ACTIVE.containsKey(e); }

    public static int charge(Entity e) { return Math.max(0, e.getPersistentData().getInt(TuneAccess.CHARGE_KEY)); }

    /** Ajoute jusqu'à {@code bottles} bouteilles ; renvoie le nombre réellement utilisé (la réserve est plafonnée). */
    public static int refill(Entity v, int bottles) {
        int free = MAX_TICKS - charge(v);
        int used = Math.min(bottles, (free + BOTTLE_TICKS - 1) / BOTTLE_TICKS);
        if (used <= 0) return 0;
        v.getPersistentData().putInt(TuneAccess.CHARGE_KEY, Math.min(MAX_TICKS, charge(v) + used * BOTTLE_TICKS));
        return used;
    }

    public static String reserve(Entity v) { return String.format(java.util.Locale.ROOT, "%.0f s", charge(v) / 20.0); }

    // ------------------------------------------------------------------ touche

    /** Reçu du client : la touche d'injection vient d'être enfoncée / relâchée. */
    public static void setKey(ServerPlayer p, boolean down) {
        if (!down) {
            for (Map.Entry<Entity, UUID> en : ACTIVE.entrySet()) if (en.getValue().equals(p.getUUID())) deactivate(en.getKey());
            return;
        }
        if (p.getVehicle() == null) return;
        Entity v = TuneAccess.nearest(p.serverLevel(), p.position(), 6);
        if (v == null) return;
        TuneAccess.Setup s = TuneAccess.Setup.read(v);
        if (s.nitro() == 0) {
            p.displayClientMessage(Component.literal("§7Ce véhicule n'a pas de nitro (kit à faire installer au banc de puissance)."), true);
            return;
        }
        if (charge(v) <= 0) {
            p.displayClientMessage(Component.literal("§cRéserve de nitro vide : rechargez avec une bouteille ou un chariot."), true);
            p.serverLevel().playSound(null, p.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8f, 1.2f);
            return;
        }
        if (ACTIVE.putIfAbsent(v, p.getUUID()) == null) {
            retune(v);
            ServerLevel l = p.serverLevel();
            l.playSound(null, v.blockPosition(), ModBanc.NITRO_SOUND.get(), SoundSource.NEUTRAL, 1.5f, 1.0f);   // nitro.ogg (11,8 s)
            SOUND_AGE.put(v, 0);
            p.displayClientMessage(Component.literal("§bNitro " + "I".repeat(s.nitro()) + " activée §7(réserve " + reserve(v) + ")"), true);
        }
    }

    /** Réapplique le réglage (nitro active ou non) et le fait suivre aux clients : MTS simule aussi la voiture chez eux. */
    private static void retune(Entity v) {
        TuneAccess.applyTune(v, TuneAccess.Setup.read(v));
        fr.minenorth.banc.network.BancNetwork.sync(v);
    }

    private static void deactivate(Entity v) {
        SOUND_AGE.remove(v);
        if (ACTIVE.remove(v) != null) retune(v);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        for (Map.Entry<Entity, UUID> en : ACTIVE.entrySet()) if (en.getValue().equals(e.getEntity().getUUID())) deactivate(en.getKey());
    }

    // ------------------------------------------------------------------ tick : consommation + flammes

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        for (Iterator<Map.Entry<Entity, UUID>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, UUID> en = it.next();
            Entity v = en.getKey();
            ServerPlayer p = e.getServer().getPlayerList().getPlayer(en.getValue());
            if (v.isRemoved() || p == null || p.getVehicle() == null) {
                it.remove();
                SOUND_AGE.remove(v);
                if (!v.isRemoved()) retune(v);
                continue;
            }
            int left = charge(v) - 1;
            v.getPersistentData().putInt(TuneAccess.CHARGE_KEY, Math.max(0, left));
            if (left <= 0) {
                it.remove();
                SOUND_AGE.remove(v);
                retune(v);
                p.displayClientMessage(Component.literal("§cNitro épuisée."), true);
                ((ServerLevel) v.level()).playSound(null, v.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.8f, 1.1f);
                continue;
            }
            if (left % 20 == 0) p.displayClientMessage(Component.literal("§bNitro : §f" + (left / 20) + " s"), true);
            int age = SOUND_AGE.merge(v, 1, Integer::sum);
            if (age >= SOUND_TICKS) {
                SOUND_AGE.put(v, 0);
                ((ServerLevel) v.level()).playSound(null, v.blockPosition(), ModBanc.NITRO_SOUND.get(), SoundSource.NEUTRAL, 1.5f, 1.0f);
            }
            int lvl = TuneAccess.Setup.read(v).nitro();
            TuneAccess.push(v, TuneAccess.NITRO_ACCEL[lvl]);   // poussée directe (la force motrice est plafonnée par l'adhérence)
            flames((ServerLevel) v.level(), v, lvl);
        }
    }

    private static void flames(ServerLevel l, Entity v, int level) {
        double[] h = TuneAccess.heading(v);
        double hx, hz;
        if (h != null) { hx = h[0]; hz = h[1]; }
        else { double yaw = Math.toRadians(v.getYRot()); hx = -Math.sin(yaw); hz = Math.cos(yaw); }
        double[] g = TuneAccess.rearGeometry(v);   // {arrière, demi-largeur, bas} d'après les boîtes de collision
        double rear = g != null ? g[0] + 0.05 : REAR, side = g != null ? Math.min(0.55, g[1] * 0.4) : SIDE, height = (g != null ? g[2] : 0) + HEIGHT;
        double bx = v.getX() - hx * rear, by = v.getY() + height, bz = v.getZ() - hz * rear;
        double px = -hz, pz = hx;   // perpendiculaire : deux échappements
        // couleur de la flamme selon le niveau : I orange, II bleu, III violet
        ParticleOptions core = switch (level) {
            case 2 -> ParticleTypes.SOUL_FIRE_FLAME;
            case 3 -> ParticleTypes.DRAGON_BREATH;
            default -> ParticleTypes.FLAME;
        };
        Vector3f color = switch (level) {
            case 2 -> new Vector3f(0.25f, 0.55f, 1.0f);
            case 3 -> new Vector3f(0.72f, 0.25f, 1.0f);
            default -> new Vector3f(1.0f, 0.55f, 0.12f);
        };
        long t = l.getGameTime();
        for (int s2 = -1; s2 <= 1; s2 += 2) {
            double x = bx + px * side * s2, z = bz + pz * side * s2;
            // flamme courte vers l'arrière (count 0 : les « offsets » sont la direction, x vitesse)
            l.sendParticles(core, x, by, z, 0, -hx, 0.03, -hz, 0.22);
            l.sendParticles(core, x, by, z, 0, -hx, 0.0, -hz, 0.32);
            l.sendParticles(new DustParticleOptions(color, 1.3f), x - hx * 0.3, by, z - hz * 0.3, 2, 0.12, 0.08, 0.12, 0.0);
            if (t % 3 == 0) l.sendParticles(ParticleTypes.FIREWORK, x, by, z, 1, 0.05, 0.05, 0.05, 0.12);   // étincelles façon feu d'artifice
            if (t % 5 == 0) l.sendParticles(ParticleTypes.SMOKE, x - hx * 0.5, by + 0.1, z - hz * 0.5, 1, 0.05, 0.05, 0.05, 0.01);
        }
    }

    // ------------------------------------------------------------------ recharge à la bouteille

    /** Clic droit sur un véhicule avec une bouteille de nitro : +15 s de réserve (si le véhicule a un kit). */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack st = e.getItemStack();
        if (!st.is(ModBanc.NITRO_BOTTLE.get()) || !TuneAccess.isVehicle(e.getTarget())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Entity v = e.getTarget();
        if (TuneAccess.Setup.read(v).nitro() == 0) {
            p.displayClientMessage(Component.literal("§cCe véhicule n'a pas de kit nitro (à installer au banc de puissance)."), true);
            return;
        }
        if (refill(v, 1) == 0) {
            p.displayClientMessage(Component.literal("§7Réserve de nitro pleine (" + reserve(v) + ")."), true);
            return;
        }
        if (!p.getAbilities().instabuild) st.shrink(1);
        ((ServerLevel) p.level()).playSound(null, v.blockPosition(), SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 1.0f, 0.8f);
        p.displayClientMessage(Component.literal("§aNitro rechargée : §f" + reserve(v)), true);
    }

    /** Clic accroupi sur le chariot : recharge le véhicule le plus proche (6 blocs) avec les bouteilles du chariot. */
    public static void refillFromCart(ServerPlayer p, NitroCartBE cart) {
        ServerLevel l = p.serverLevel();
        Entity v = TuneAccess.nearest(l, Vec3.atCenterOf(cart.getBlockPos()), 6);
        if (v == null) { p.displayClientMessage(Component.literal("§7Aucun véhicule à moins de 6 blocs du chariot."), true); return; }
        if (TuneAccess.Setup.read(v).nitro() == 0) {
            p.displayClientMessage(Component.literal("§cCe véhicule n'a pas de kit nitro (à installer au banc de puissance)."), true);
            return;
        }
        int have = cart.bottles();
        int used = refill(v, have);
        if (used == 0) {
            p.displayClientMessage(Component.literal(have == 0 ? "§cLe chariot est vide." : "§7Réserve de nitro pleine (" + reserve(v) + ")."), true);
            return;
        }
        cart.take(used);
        l.playSound(null, cart.getBlockPos(), SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0f, 0.8f);
        p.displayClientMessage(Component.literal("§aNitro rechargée avec " + used + " bouteille(s) : §f" + reserve(v)), true);
    }
}
