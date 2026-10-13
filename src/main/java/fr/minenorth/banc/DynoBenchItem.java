package fr.minenorth.banc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pose le banc dynamométrique d'un coup : 4 de large (à droite du joueur) sur 8 de long (devant lui), à partir de la case visée.
 * Réservé au patron ou à un gérant d'une entreprise active ; il faut 32 cases libres.
 */
public class DynoBenchItem extends BlockItem {
    public DynoBenchItem(Block block, Properties props) { super(block, props); }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer sp)) return InteractionResult.FAIL;
        int company = DynoService.managedCompany(sp);
        if (company < 0) {
            sp.displayClientMessage(Component.literal("§cSeul le patron ou un gérant d'une entreprise active peut poser le banc."), true);
            return InteractionResult.FAIL;
        }
        Direction f = ctx.getHorizontalDirection();
        BlockPos origin = ctx.getClickedPos();
        for (int i = 0; i < DynoBenchBlock.WIDTH; i++) {
            for (int j = 0; j < DynoBenchBlock.LENGTH; j++) {
                BlockPos p = DynoBenchBlock.cell(origin, f, i, j);
                if (level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p) || !level.getBlockState(p).canBeReplaced()) {
                    sp.displayClientMessage(Component.literal("§cIl faut un espace libre de " + DynoBenchBlock.WIDTH + " × " + DynoBenchBlock.LENGTH
                            + " blocs (à ta droite et devant toi)."), true);
                    return InteractionResult.FAIL;
                }
            }
        }
        for (int i = 0; i < DynoBenchBlock.WIDTH; i++) {
            for (int j = 0; j < DynoBenchBlock.LENGTH; j++) {
                BlockPos p = DynoBenchBlock.cell(origin, f, i, j);
                BlockState st = getBlock().defaultBlockState().setValue(DynoBenchBlock.FACING, f).setValue(DynoBenchBlock.PART, DynoBenchBlock.partAt(i, j));
                level.setBlock(p, st, 3);
                if (level.getBlockEntity(p) instanceof DynoBE be) {
                    be.companyId = company;
                    be.bi = i;
                    be.bj = j;
                    be.setChanged();
                }
            }
        }
        level.playSound(null, origin, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.8f, 0.8f);
        if (!sp.isCreative()) ctx.getItemInHand().shrink(1);
        return InteractionResult.SUCCESS;
    }
}
