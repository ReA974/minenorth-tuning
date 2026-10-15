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
    private static final double VEHICLE_RADIUS = 12.0, USE_DISTANCE = 14.0;
    /** Grades acceptés par EntrepriseApi.canOpen : 99 = tout le personnel, 0 = patron et grade le plus haut (gérants). */
    private static final int STAFF = 99, MANAGER = 0;
    private static final String[] STAGE_NAMES = {"Origine (usine)", "Stage 1", "Stage 2", "Stage 3"};
    private static final String[] BOOST_NAMES = {"Aucune", "Compresseur", "Turbo"}, AXLE_NAMES = {"Pont d'origine", "Pont court", "Pont long"},
            TIRE_NAMES = {"Pneus d'origine", "Pneus sport", "Pneus course"}, BRAKE_NAMES = {"Freins d'origine", "Freins sport", "Freins course"},
            NITRO_NAMES = {"Aucun kit", "Nitro I", "Nitro II", "Nitro III"}, NITRO_COLORS = {"", "orange", "bleue", "violette"};

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
            TuneAccess.Setup cur0 = TuneAccess.Setup.read(v);
            TuneAccess.Engine f = TuneAccess.factory(v), cur = TuneAccess.current(v);
            if (f == null || cur == null) {
                lines.add("Moteur illisible : ce véhicule ne peut pas être reprogrammé.");
            } else {
                double idx = cur.power() * 100, base = f.power() * 100;
                lines.add(String.format(Locale.ROOT, "Indice de puissance : %.0f  (origine %.0f, %s)", idx, base, pct(base == 0 ? 1 : idx / base)));
                lines.add(String.format(Locale.ROOT, "Régime maximal : %.0f tr/min  (origine %.0f)", cur.maxRpm(), f.maxRpm()));
                lines.add("Boîte : " + (f.gears() > 0 ? f.gears() + " rapports" : "automatique / inconnue"));
                lines.add("Options : " + BOOST_NAMES[cur0.boost()] + ", " + AXLE_NAMES[cur0.axle()] + ", " + TIRE_NAMES[cur0.tires()] + ", " + BRAKE_NAMES[cur0.brakes()] + ", " + NITRO_NAMES[cur0.nitro()]
                        + (cur0.nitro() > 0 ? " (réserve " + NitroService.reserve(v) + ")" : ""));
                for (int i = 0; i <= TuneAccess.MAX_STAGE; i++) {
                    String detail = i == 0 ? "Remise aux réglages d'usine"
                            : "Puissance " + pct(TuneAccess.POWER[i]) + ", régime " + pct(TuneAccess.RPM[i]) + ", adhérence " + pct(TuneAccess.GRIP[i]) + ", usure " + pct(TuneAccess.WEAR[i]);
                    rows.add(new Row(0, STAGE_NAMES[i], detail, i == cur0.stage()));
                }
                for (int i = 0; i < BOOST_NAMES.length; i++)
                    rows.add(new Row(1, BOOST_NAMES[i], i == 0 ? "Retire la suralimentation"
                            : "Puissance " + pct(1 + TuneAccess.BOOST_POWER[i]) + ", usure " + pct(TuneAccess.BOOST_WEAR[i]) + ", consomme plus", i == cur0.boost()));
                for (int i = 0; i < AXLE_NAMES.length; i++)
                    rows.add(new Row(2, AXLE_NAMES[i], i == 0 ? "Rapport d'usine"
                            : "Accélération " + pct(TuneAccess.AXLE[i]) + ", vitesse de pointe " + pct(1 / TuneAccess.AXLE[i]), i == cur0.axle()));
                for (int i = 0; i < TIRE_NAMES.length; i++)
                    rows.add(new Row(3, TIRE_NAMES[i], i == 0 ? "Pneus d'usine"
                            : "Adhérence " + pct(TuneAccess.TIRE_MOTIVE[i]) + ", tenue de route " + pct(TuneAccess.TIRE_LATERAL[i]), i == cur0.tires()));
                for (int i = 0; i < BRAKE_NAMES.length; i++)
                    rows.add(new Row(4, BRAKE_NAMES[i], i == 0 ? "Freins d'usine" : "Freinage " + pct(TuneAccess.BRAKES[i]), i == cur0.brakes()));
                for (int i = 0; i < NITRO_NAMES.length; i++)
                    rows.add(new Row(5, NITRO_NAMES[i], i == 0 ? "Retire le kit nitro"
                            : "Puissance " + pct(1 + TuneAccess.NITRO_POWER[i]) + " pendant l'injection, flamme " + NITRO_COLORS[i] + ". Touche d'injection au volant", i == cur0.nitro()));
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
        TuneAccess.Setup old = TuneAccess.Setup.read(v);
        int max = a.group() == TuneAccess.Setup.STAGE || a.group() == TuneAccess.Setup.NITRO_G ? TuneAccess.MAX_STAGE : 2;
        if (a.group() < 0 || a.group() > TuneAccess.Setup.NITRO_G || a.index() < 0 || a.index() > max) return;
        TuneAccess.Setup next = old.with(a.group(), a.index());
        if (next.equals(old)) { send(p, be, "Le véhicule est déjà réglé ainsi.", false); return; }
        if (!TuneAccess.applyTune(v, next)) {
            TuneAccess.applyTune(v, old);
            send(p, be, "Réglage impossible sur ce véhicule (moteur ou pièce non pris en charge).", false);
            return;
        }
        next.write(v);
        TuneAccess.Engine fa = TuneAccess.factory(v), now = TuneAccess.current(v);
        com.mojang.logging.LogUtils.getLogger().info("[Banc] {} : {} -> {} ; puissance {} (origine {}), regime max {} -> {}",
                TuneAccess.name(v), old, next, now == null ? "?" : now.power(), fa == null ? "?" : fa.power(),
                fa == null ? "?" : fa.maxRpm(), now == null ? "?" : now.maxRpm());
        TuneService.track(v);
        String what = switch (a.group()) {
            case 0 -> a.index() == 0 ? "Réglages d'usine rétablis." : "Moteur reprogrammé : " + STAGE_NAMES[a.index()] + ".";
            case 1 -> a.index() == 0 ? "Suralimentation retirée." : BOOST_NAMES[a.index()] + " installé.";
            case 2 -> AXLE_NAMES[a.index()] + " installé.";
            case 3 -> TIRE_NAMES[a.index()] + " montés.";
            case 5 -> a.index() == 0 ? "Kit nitro retiré." : NITRO_NAMES[a.index()] + " installé : chargez-le avec des bouteilles (clic droit sur le véhicule ou chariot).";
            default -> BRAKE_NAMES[a.index()] + " montés.";
        };
        send(p, be, what + " Pensez à facturer le client.", true);
    }

    // ------------------------------------------------------------------ casse

    /** Casser le banc : réservé aux patron / gérants de l'entreprise propriétaire et aux OP. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getState().getBlock() instanceof DynoBlock || e.getState().getBlock() instanceof DynoBenchBlock) || !(e.getPlayer() instanceof ServerPlayer sp)) return;
        BlockPos pos = e.getPos();
        if (sp.level().getBlockEntity(pos) instanceof DynoBE be && be.companyId >= 0 && !sp.hasPermissions(2)
                && !EntrepriseApi.canOpen(sp, be.companyId, MANAGER)) {
            e.setCanceled(true);
            sp.displayClientMessage(Component.literal("§cCe banc appartient à une entreprise."), true);
        }
    }
}
