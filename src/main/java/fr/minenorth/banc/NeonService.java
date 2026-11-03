package fr.minenorth.banc;

import fr.minenorth.banc.network.BancNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Néons sous caisse : posés au banc par le personnel (un kit néons consommé à la pose), rangés dans les données persistantes du véhicule
 * (clé {@value #TAG}, donc dans son NBT au garage, à la fourrière et à la vente), allumés / éteints par le conducteur avec sa touche.
 * Le serveur envoie l'état aux joueurs qui voient le véhicule ; le rendu est fait chez eux (client/ClientNeons).
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class NeonService {
    private NeonService() {}

    public static final String TAG = "mnrp_neons";
    public static final int MODE_FIXED = 0, MODE_BREATH = 1, MODE_RAINBOW = 2, MODE_CHASE = 3;
    public static final String[] MODE_NAMES = {"Fixe", "Respiration", "Arc-en-ciel", "Chenillard"};

    /** État des néons d'un véhicule : {@code color} en RGB (0xRRGGBB). */
    public record Neon(boolean installed, boolean on, int color, int mode) {
        public static final Neon NONE = new Neon(false, false, 0x20AAEB, MODE_FIXED);

        public static Neon read(Entity e) {
            CompoundTag d = e.getPersistentData();
            if (!d.contains(TAG, 10)) return NONE;
            CompoundTag t = d.getCompound(TAG);
            return new Neon(true, t.getBoolean("Allume"), t.getInt("Couleur") & 0xFFFFFF, Math.max(0, Math.min(MODE_NAMES.length - 1, t.getInt("Mode"))));
        }

        public void write(Entity e) {
            if (!installed) { e.getPersistentData().remove(TAG); return; }
            CompoundTag t = new CompoundTag();
            t.putBoolean("Allume", on);
            t.putInt("Couleur", color & 0xFFFFFF);
            t.putInt("Mode", mode);
            e.getPersistentData().put(TAG, t);
        }
    }

    // ------------------------------------------------------------------ synchronisation

    /** Véhicules chargés -> dernier état envoyé (null = jamais envoyé). Le garage restaure les données APRÈS l'apparition : on compare chaque seconde. */
    private static final Map<Entity, Neon> SENT = new ConcurrentHashMap<>();
    private static int scan;

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && TuneAccess.isVehicle(e.getEntity())) SENT.put(e.getEntity(), Neon.NONE);
    }

    /** Un joueur voit apparaître un véhicule équipé : il reçoit l'état de ses néons. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking e) {
        if (e.getEntity() instanceof ServerPlayer p && TuneAccess.isVehicle(e.getTarget()) && Neon.read(e.getTarget()).installed())
            BancNetwork.neonTo(p, e.getTarget(), Neon.read(e.getTarget()));
    }

    /** Écrit l'état et l'envoie tout de suite aux joueurs qui voient le véhicule. */
    public static void set(Entity v, Neon n) {
        n.write(v);
        SENT.put(v, n);
        BancNetwork.neonSync(v, n);
    }

    /** Renvoie l'état de tous les véhicules équipés et chargés de ce modèle (leur forme vient de changer) ; renvoie leur nombre. */
    public static int resyncModel(String model) {
        int n = 0;
        for (Entity v : SENT.keySet()) {
            if (v.isRemoved() || !model.equals(TuneAccess.modelKey(v))) continue;
            Neon now = Neon.read(v);
            if (!now.installed()) continue;
            BancNetwork.neonSync(v, now);
            n++;
        }
        return n;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++scan < 20) return;
        scan = 0;
        for (Iterator<Map.Entry<Entity, Neon>> it = SENT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Neon> en = it.next();
            Entity v = en.getKey();
            if (v.isRemoved()) { it.remove(); continue; }
            Neon now = Neon.read(v);
            if (now.equals(en.getValue())) continue;
            en.setValue(now);
            BancNetwork.neonSync(v, now);
        }
    }

    // ------------------------------------------------------------------ touche du conducteur

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Map<UUID, Integer> LAST_KEY = new ConcurrentHashMap<>();

    /** Reçu du client : le conducteur allume / éteint ses néons. */
    public static void toggle(ServerPlayer p) {
        int now = p.server.getTickCount();
        Integer last = LAST_KEY.put(p.getUUID(), now);
        if (last != null && now - last < 5) return;
        Entity v = TuneAccess.ridden(p);
        LOG.info("[Banc] Touche neons : {} -> vehicule {} (conducteur : {})", p.getGameProfile().getName(),
                v == null ? "aucun" : TuneAccess.name(v), v != null && TuneAccess.isDriver(v, p));
        if (v == null) {
            if (p.getVehicle() != null) p.displayClientMessage(Component.literal("§7Montez au volant d'un véhicule pour commander les néons."), true);
            return;
        }
        Neon n = Neon.read(v);
        if (!n.installed()) {
            p.displayClientMessage(Component.literal("§7Ce véhicule n'a pas de néons (à faire poser au banc d'un garage)."), true);
            return;
        }
        if (!TuneAccess.isDriver(v, p)) {
            p.displayClientMessage(Component.literal("§7Seul le conducteur commande les néons."), true);
            return;
        }
        Neon next = new Neon(true, !n.on(), n.color(), n.mode());
        set(v, next);
        p.serverLevel().playSound(null, p.blockPosition(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.5f, next.on() ? 1.4f : 0.9f);
        p.displayClientMessage(Component.literal(next.on() ? "§bNéons allumés §7(" + MODE_NAMES[n.mode()] + ")" : "§7Néons éteints"), true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { LAST_KEY.remove(e.getEntity().getUUID()); }
}
