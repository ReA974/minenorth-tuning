package fr.minenorth.banc;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Objet du banc de puissance : seul un patron ou un gérant d'une entreprise active peut le poser. */
public class DynoItem extends BlockItem {
    public DynoItem(Block block, Properties props) { super(block, props); }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        if (!ctx.getLevel().isClientSide && ctx.getPlayer() instanceof ServerPlayer sp && DynoService.managedCompany(sp) < 0) {
            sp.displayClientMessage(Component.literal("§cSeul le patron ou un gérant d'une entreprise active peut poser le banc de puissance."), true);
            return InteractionResult.FAIL;
        }
        return super.place(ctx);
    }
}
