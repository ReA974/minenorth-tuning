package fr.minenorth.banc;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * /mnbanc debug (et /banc debug) : affiche, pour le véhicule MTS le plus proche (8 blocs), les valeurs d'usine et les valeurs réellement en vigueur (OP).
 * « /mnbanc » existe parce que sur Arclight un plugin Bukkit peut déjà posséder « /banc » (il passe alors avant la commande du mod).
 */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class BancCommands {
    private BancCommands() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name).requires(s -> s.hasPermission(2))
                .then(Commands.literal("debug").executes(c -> debug(c.getSource(), false)))
                .then(Commands.literal("apply").executes(c -> debug(c.getSource(), true)));
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(root("mnbanc"));
        e.getDispatcher().register(root("banc"));
    }

    /** apply = force l'application du réglage mémorisé sur le véhicule et affiche le détail de chaque étape (puis le diagnostic). */
    private static int debug(CommandSourceStack src, boolean apply) {
        ServerPlayer p;
        try {
            p = src.getPlayerOrException();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Commande à lancer en jeu (pas depuis la console)."));
            return 0;
        }
        p.sendSystemMessage(Component.literal("§6[Banc] §7Recherche d'un véhicule MTS à moins de 8 blocs…"));
        try {
            Entity v = TuneAccess.nearest(p.serverLevel(), p.position(), 8);
            if (v == null) {
                p.sendSystemMessage(Component.literal("§cAucun véhicule MTS à moins de 8 blocs."));
                return 0;
            }
            p.sendSystemMessage(Component.literal("§6[Banc] §f" + TuneAccess.name(v)));
            if (apply) {
                java.util.List<String> log = new java.util.ArrayList<>();
                TuneAccess.Setup setup = TuneAccess.Setup.read(v);
                boolean ok = TuneAccess.applyTune(v, setup, log);
                TuneService.track(v);
                p.sendSystemMessage(Component.literal(ok ? "§aRéglage appliqué." : "§cRéglage NON appliqué (ou partiel)."));
                for (String line : log) p.sendSystemMessage(Component.literal("§e" + line));
            }
            for (String line : TuneAccess.diagnose(v)) p.sendSystemMessage(Component.literal("§7" + line));
            return 1;
        } catch (Throwable t) {
            LOG.error("[Banc] /mnbanc debug a échoué", t);
            p.sendSystemMessage(Component.literal("§cErreur : " + t + " (détails dans le log du serveur)."));
            return 0;
        }
    }
}
