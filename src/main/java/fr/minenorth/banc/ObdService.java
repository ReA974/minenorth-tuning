package fr.minenorth.banc;

import fr.minenorth.api.MtsTires;
import fr.minenorth.banc.network.BancNetwork;
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
        List<BancNetwork.EngineInfo> infos = new java.util.ArrayList<>();
        int n = 0;
        for (Object eng : engines) {
            Object def = MtsTires.get(eng, "definition");
            double max = MtsTires.number(MtsTires.get(def, "general"), "health", 0);
            infos.add(new BancNetwork.EngineInfo(engines.size() > 1 ? "Moteur " + (++n) : "Moteur", (float) health(eng),
                    (float) (max > 0 ? 100 * Math.min(1, var(eng, "damageVar") / max) : 0), (float) var(eng, "hoursVar"),
                    (float) MtsTires.number(eng, "temp", 0), (float) MtsTires.number(eng, "pressure", 0), (float) MtsTires.number(eng, "rpm", 0),
                    Boolean.TRUE.equals(MtsTires.get(eng, "running"))));
        }
        TuneAccess.Setup s = TuneAccess.Setup.read(v);
        String prep = s.stock() ? "Préparation : d'origine" : "Préparation : " + (s.stage() > 0 ? "Stage " + s.stage() : "origine") + (s.nitro() > 0 ? ", nitro " + s.nitro() : "");
        int flat = 0;
        for (Object w : wheels) if (MtsTires.isFlat(w)) flat++;
        BancNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p), new BancNetwork.ObdReport(TuneAccess.name(v), infos, prep, wheels.size(), flat));
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
