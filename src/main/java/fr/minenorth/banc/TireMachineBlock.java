package fr.minenorth.banc;

import fr.minenorth.api.MtsTires;
import fr.minenorth.entreprises.api.EntrepriseApi;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Machine à pneus : clic droit = répare les pneus crevés du véhicule le plus proche (6 blocs), à raison d'un kit anti-crevaison par pneu pris
 * dans l'inventaire du mécanicien. Réservé au personnel d'une entreprise et aux OP.
 */
public class TireMachineBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);
    private static final double RANGE = 6;

    public TireMachineBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(3.0f).sound(SoundType.METAL).noOcclusion());
    }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override
    public InteractionResult use(BlockState s, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.CONSUME;
        if (!p.hasPermissions(2) && EntrepriseApi.companyName(p).isEmpty()) {
            p.displayClientMessage(Component.literal("§cRéservé au personnel d'une entreprise."), true);
            return InteractionResult.CONSUME;
        }
        Entity v = TuneAccess.nearest((ServerLevel) level, Vec3.atCenterOf(pos), RANGE);
        if (v == null) { p.displayClientMessage(Component.literal("§7Aucun véhicule à moins de 6 blocs de la machine."), true); return InteractionResult.CONSUME; }
        List<Object> flat = MtsTires.wheels(v).stream().filter(MtsTires::isFlat).toList();
        if (flat.isEmpty()) { p.displayClientMessage(Component.literal("§7Aucun pneu crevé sur ce véhicule."), true); return InteractionResult.CONSUME; }
        int fixed = 0;
        for (Object w : flat) {
            if (!take(p)) break;
            if (MtsTires.repair(w)) fixed++;
        }
        if (fixed == 0) {
            p.displayClientMessage(Component.literal("§cIl faut un kit anti-crevaison par pneu (" + flat.size() + " crevé(s))."), true);
            return InteractionResult.CONSUME;
        }
        level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.5f);
        p.displayClientMessage(Component.literal("§a" + fixed + " pneu(s) réparé(s)" + (fixed < flat.size() ? "§e, il en reste " + (flat.size() - fixed) + " (kits manquants)" : "") + ". Pensez à facturer."), true);
        return InteractionResult.CONSUME;
    }

    /** Consomme un kit anti-crevaison de l'inventaire (gratuit en créatif). */
    private static boolean take(ServerPlayer p) {
        if (p.getAbilities().instabuild) return true;
        for (ItemStack st : p.getInventory().items)
            if (st.is(ModBanc.TIRE_KIT.get())) { st.shrink(1); return true; }
        return false;
    }
}
