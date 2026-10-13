package fr.minenorth.banc;

import fr.minenorth.banc.network.BancNetwork;
import fr.minenorth.banc.network.BancNetwork.Row;
import fr.minenorth.entreprises.api.EntrepriseApi;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Règles serveur du banc de puissance. Le service est rendu par le PERSONNEL de l'entreprise (patron, gérants, employés) sur le
 * véhicule d'un client ; le client est facturé par le système de factures du mod Entreprises : aucun paiement ici.
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class DynoService {
    private DynoService() {}

    /** Rayon de recherche du véhicule autour du banc, et distance maximale du joueur au banc (blocs). */
    private static final double VEHICLE_RADIUS = 6.0, USE_DISTANCE = 8.0;
    /** Grades acceptés par EntrepriseApi.canOpen : 99 = tout le personnel, 0 = patron et grade le plus haut (gérants). */
    private static final int STAFF = 99, MANAGER = 0;
    private static final String[] STAGE_NAMES = {"Origine (usine)", "Stage 1", "Stage 2", "Stage 3"};

    /** Identifiant de l'entreprise active du joueur s'il en est patron ou gérant (peut poser le banc), sinon -1. */
    public static int managedCompany(ServerPlayer p) {
        String name = EntrepriseApi.companyName(p);
        if (name.isEmpty()) return -1;
        for (String line : EntrepriseApi.listCompanies(p.server)) {
            int bar = line.indexOf('|');
            if (bar <= 0 || !line.substring(bar + 1).equals(name)) continue;
            try {
                int id = Integer.parseInt(line.substring(0, bar));
                return EntrepriseApi.canOpen(p, id, MANAGER) ? id : -1;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static boolean isStaff(ServerPlayer p, DynoBE be) {
        return p.hasPermissions(2) || EntrepriseApi.canOpen(p, be.companyId, STAFF);
    }

    private static Entity vehicleNear(ServerPlayer p, DynoBE be) {
        return TuneAccess.nearest((ServerLevel) p.level(), Vec3.atCenterOf(be.getBlockPos()), VEHICLE_RADIUS);
    }

    // ------------------------------------------------------------------ écran

    public static void open(ServerPlayer p, DynoBE be) {
        if (be.companyId < 0 || !isStaff(p, be)) {
            p.displayClientMessage(Component.literal(be.companyId < 0 ? "§cBanc hors service." : "§cRéservé au personnel de l'entreprise."), true);
            return;
        }
        send(p, be, "", true);
    }

    private static String pct(double factor) { return String.format(Locale.ROOT, "%+.0f %%", (factor - 1) * 100); }

    private static void send(ServerPlayer p, DynoBE be, String message, boolean ok) {
        List<Row> rows = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String vehicle = "";
        Entity v = vehicleNear(p, be);
        if (v != null) {
            vehicle = TuneAccess.name(v);
            int stage = TuneAccess.stageOf(v);
            TuneAccess.Engine f = TuneAccess.factory(v), cur = TuneAccess.current(v);
            if (f == null || cur == null) {
                lines.add("Moteur illisible : ce véhicule ne peut pas être reprogrammé.");
            } else {
                double idx = cur.power() * 100, base = f.power() * 100;
                lines.add(String.format(Locale.ROOT, "Indice de puissance : %.0f  (origine %.0f, %s)", idx, base, pct(base == 0 ? 1 : idx / base)));
                lines.add(String.format(Locale.ROOT, "Régime maximal : %.0f tr/min  (origine %.0f)", cur.maxRpm(), f.maxRpm()));
                lines.add("Boîte : " + (f.gears() > 0 ? f.gears() + " rapports" : "automatique / inconnue"));
                for (int i = 0; i <= TuneAccess.MAX_STAGE; i++) {
                    String detail = i == 0 ? "Remise aux réglages d'usine"
                            : "Puissance " + pct(TuneAccess.POWER[i]) + ", régime " + pct(TuneAccess.RPM[i]) + ", usure " + pct(TuneAccess.WEAR[i]);
                    rows.add(new Row(STAGE_NAMES[i], detail, i == stage));
                }
            }
        }
        BancNetwork.send(p, new BancNetwork.State(be.getBlockPos(), EntrepriseApi.companyName(p.server, be.companyId), vehicle, message, ok, lines, rows));
    }

    // ------------------------------------------------------------------ actions

    public static void handle(ServerPlayer p, BancNetwork.Action a) {
        if (!p.level().isLoaded(a.pos()) || p.blockPosition().distSqr(a.pos()) > USE_DISTANCE * USE_DISTANCE) return;
        if (!(p.level().getBlockEntity(a.pos()) instanceof DynoBE be) || be.companyId < 0 || !isStaff(p, be)) return;
        if (a.action() != BancNetwork.ACT_APPLY) { send(p, be, "", true); return; }

        if (p.getVehicle() != null) { send(p, be, "Descendez du véhicule d'abord.", false); return; }
        Entity v = vehicleNear(p, be);
        if (v == null) { send(p, be, "Aucun véhicule à proximité du banc.", false); return; }
        int stage = a.index(), old = TuneAccess.stageOf(v);
        if (stage < 0 || stage > TuneAccess.MAX_STAGE) return;
        if (stage == old) { send(p, be, "Le véhicule est déjà à ce niveau.", false); return; }
        if (!TuneAccess.applyTune(v, stage)) {
            TuneAccess.applyTune(v, old);
            send(p, be, "Reprogrammation impossible sur ce véhicule (moteur non pris en charge).", false);
            return;
        }
        v.getPersistentData().putInt(TuneAccess.TUNE, stage);
        TuneService.track(v);
        send(p, be, stage == 0 ? "Réglages d'usine rétablis." : "Moteur reprogrammé : " + STAGE_NAMES[stage] + ". Pensez à facturer le client.", true);
    }

    // ------------------------------------------------------------------ casse

    /** Casser le banc : réservé aux patron / gérants de l'entreprise propriétaire et aux OP. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getState().getBlock() instanceof DynoBlock) || !(e.getPlayer() instanceof ServerPlayer sp)) return;
        BlockPos pos = e.getPos();
        if (sp.level().getBlockEntity(pos) instanceof DynoBE be && be.companyId >= 0 && !sp.hasPermissions(2)
                && !EntrepriseApi.canOpen(sp, be.companyId, MANAGER)) {
            e.setCanceled(true);
            sp.displayClientMessage(Component.literal("§cCe banc appartient à une entreprise."), true);
        }
    }
}
