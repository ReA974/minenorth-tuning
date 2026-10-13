package fr.minenorth.banc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Banc de puissance d'une entreprise. Clic droit (personnel de l'entreprise : patron, gérants, employés) = diagnostic moteur et
 * reprogrammation du véhicule garé à côté. Pose et casse réservées au patron / aux gérants (et aux OP).
 */
public class DynoBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private final VoxelShape shape;

    public DynoBlock() { this(Block.box(0, 0, 0, 16, 11, 16)); }

    public DynoBlock(VoxelShape shape) {

        super(BlockBehaviour.Properties.of().strength(3.0f, 6.0f).sound(SoundType.METAL).noOcclusion());
        this.shape = shape;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }

    @Override @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return shape; }

    @Override @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && placer instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof DynoBE be) {
            int id = DynoService.managedCompany(sp);
            if (id >= 0) { be.companyId = id; be.setChanged(); }
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DynoBE(pos, state); }

    @Override @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof DynoBE be)
            DynoService.open(sp, be);
        return InteractionResult.CONSUME;
    }
}
