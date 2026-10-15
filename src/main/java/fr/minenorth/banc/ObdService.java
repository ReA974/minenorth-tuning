package fr.minenorth.banc;

import fr.minenorth.api.MtsTires;
import fr.minenorth.entreprises.api.EntrepriseApi;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * Outil OBD2 (clic droit sur un véhicule : rapport sur le moteur et les pneus) et kit de réparation moteur (clic droit : moteur remis à neuf,
 * réservé au personnel d'une entreprise et aux OP). La santé du moteur combine ses dégâts MTS et ses heures de fonctionnement.
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class ObdService {
    private ObdService() {}

    /** Heures de fonctionnement au bout desquelles l'usure compte pour 40 % de la santé (le reste vient des dégâts). */
    private static final double LIFE_HOURS = 400;

    private static double var(Object holder, String name) { return MtsTires.number(MtsTires.get(holder, name), "currentValue", 0); }

    private static void setVar(Object holder, String name, double value) {
        Object v = MtsTires.get(holder, name);
        if (v == null) return;
        try { v.getClass().getMethod("setTo", double.class, boolean.class).invoke(v, value, true); } catch (Throwable ignored) {}
    }

    /** Santé 0-100 d'un moteur MTS. */
    private static double health(Object eng) {
        Object def = MtsTires.get(eng, "definition");
        double max = MtsTires.number(MtsTires.get(def, "general"), "health", 0);
        double dmg = max > 0 ? Math.min(1, var(eng, "damageVar") / max) : 0;
        double wear = Math.min(1, var(eng, "hoursVar") / LIFE_HOURS);
        return 100 * (1 - 0.6 * dmg - 0.4 * wear);
    }

    private static String grade(double h) {
        return h >= 85 ? "§aExcellent" : h >= 60 ? "§2Bon" : h >= 35 ? "§eUsé" : h >= 15 ? "§6Mauvais" : "§cCritique";
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND || !TuneAccess.isVehicle(e.getTarget())) return;
        ItemStack st = e.getItemStack();
        boolean tool = st.is(ModBanc.OBD_TOOL.get()), kit = st.is(ModBanc.ENGINE_KIT.get());
        if (!tool && !kit) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (tool) report(p, e.getTarget());
        else repair(p, e.getTarget(), st);
    }

    private static void report(ServerPlayer p, Entity v) {
        List<Object> engines = MtsTires.parts(v, "PartEngine");
        List<Object> wheels = MtsTires.wheels(v);
        p.sendSystemMessage(Component.literal("§8§m          §r §9OBD2 §8| §f" + TuneAccess.name(v) + " §8§m          "));
        if (engines.isEmpty()) {
            p.sendSystemMessage(Component.literal("§cAucun moteur détecté."));
        }
        int n = 0;
        for (Object eng : engines) {
            double h = health(eng);
            Object def = MtsTires.get(eng, "definition");
            double max = MtsTires.number(MtsTires.get(def, "general"), "health", 0);
            String label = engines.size() > 1 ? "Moteur " + (++n) : "Moteur";
            p.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§7%s : %s §f%.0f %%", label, grade(h), Math.max(0, h))));
            p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "§7  Dégâts §f%.0f %%§7, heures §f%.1f h§7, température §f%.0f °C§7, huile §f%.0f§7, régime §f%.0f tr/min §7(%s§7)",
                    max > 0 ? 100 * Math.min(1, var(eng, "damageVar") / max) : 0, var(eng, "hoursVar"), MtsTires.number(eng, "temp", 0),
                    MtsTires.number(eng, "pressure", 0), MtsTires.number(eng, "rpm", 0),
                    Boolean.TRUE.equals(MtsTires.get(eng, "running")) ? "§amoteur allumé" : "§7éteint")));
            if (h < 35) p.sendSystemMessage(Component.literal("§c  ⚠ Moteur à réviser : utilisez un kit de réparation moteur."));
            if (MtsTires.number(eng, "temp", 0) > 100) p.sendSystemMessage(Component.literal("§c  ⚠ Surchauffe."));
        }
        TuneAccess.Setup s = TuneAccess.Setup.read(v);
        if (!s.stock()) p.sendSystemMessage(Component.literal("§7Préparation : §f" + (s.stage() > 0 ? "Stage " + s.stage() : "origine") + (s.nitro() > 0 ? "§7, nitro " + s.nitro() : "")));
        if (!wheels.isEmpty()) {
            int flat = 0;
            for (Object w : wheels) if (MtsTires.isFlat(w)) flat++;
            p.sendSystemMessage(Component.literal(flat == 0 ? "§7Pneus : §aen bon état (" + wheels.size() + ")" : "§7Pneus : §c" + flat + " crevé(s) §7sur " + wheels.size()));
        }
        ((ServerLevel) p.level()).playSound(null, v.blockPosition(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.8f, 1.6f);
    }

    private static void repair(ServerPlayer p, Entity v, ItemStack st) {
        if (!p.hasPermissions(2) && EntrepriseApi.companyName(p).isEmpty()) {
            p.displayClientMessage(Component.literal("§cRéservé au personnel d'une entreprise."), true);
            return;
        }
        List<Object> engines = MtsTires.parts(v, "PartEngine");
        if (engines.isEmpty()) { p.displayClientMessage(Component.literal("§cAucun moteur à réparer."), true); return; }
        for (Object eng : engines)
            if (Boolean.TRUE.equals(MtsTires.get(eng, "running"))) { p.displayClientMessage(Component.literal("§cCoupez le moteur d'abord."), true); return; }
        boolean needed = false;
        for (Object eng : engines) if (health(eng) < 99.5) needed = true;
        if (!needed) { p.displayClientMessage(Component.literal("§7Le moteur est déjà en parfait état."), true); return; }
        for (Object eng : engines) {
            setVar(eng, "damageVar", 0);
            setVar(eng, "hoursVar", 0);
            try {
                java.lang.reflect.Field f = eng.getClass().getField("outOfHealth");
                f.setBoolean(eng, false);
            } catch (Throwable ignored) {}
        }
        if (!p.getAbilities().instabuild) st.shrink(1);
        ((ServerLevel) p.level()).playSound(null, v.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.7f, 1.2f);
        p.displayClientMessage(Component.literal("§aMoteur remis à neuf."), true);
    }
}
