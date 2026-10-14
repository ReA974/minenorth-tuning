package fr.minenorth.banc;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** /banc debug : affiche, pour le véhicule MTS le plus proche (8 blocs), les valeurs d'usine et les valeurs réellement en vigueur (OP). */
@Mod.EventBusSubscriber(modid = MineNorthBanc.MOD_ID)
public final class BancCommands {
    private BancCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("banc").requires(s -> s.hasPermission(2))
                .then(Commands.literal("debug").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Entity v = TuneAccess.nearest(p.serverLevel(), p.position(), 8);
                    if (v == null) {
                        c.getSource().sendFailure(Component.literal("Aucun véhicule MTS à moins de 8 blocs."));
                        return 0;
                    }
                    c.getSource().sendSuccess(() -> Component.literal("§6[Banc] §f" + TuneAccess.name(v)), false);
                    for (String line : TuneAccess.diagnose(v)) c.getSource().sendSuccess(() -> Component.literal("§7" + line), false);
                    return 1;
                })));
    }
}
