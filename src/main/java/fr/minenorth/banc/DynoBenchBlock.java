package fr.minenorth.banc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Banc dynamométrique : plateforme basse de 4 de large sur 6 de long (32 blocs identiques, un par case), avec quatre paires de rouleaux
 * pour les roues. On roule dessus ; clic droit sur n'importe quelle case = même service que le banc de puissance (diagnostic et
 * reprogrammation du véhicule, personnel de l'entreprise). Casser une case retire tout le banc ; seule la case d'origine rend l'objet.
 */
public class DynoBenchBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    /** Largeur (vers la droite du joueur) et longueur (dans le sens où il regarde), en blocs. */
    public static final int WIDTH = 4, LENGTH = 6;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);

    public enum Part implements StringRepresentable {
        ORIGIN, DECK, ROLLER;

        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public DynoBenchBlock() {
        super(BlockBehaviour.Properties.of().strength(3.0f, 6.0f).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.DECK));
    }

    /** Case (i, j) du banc : i vers la droite, j vers l'avant, à partir de la case d'origine. */
    public static BlockPos cell(BlockPos origin, Direction facing, int i, int j) {
        return origin.relative(facing.getClockWise(), i).relative(facing, j);
    }

    /** Rouleaux : deux essieux (j = 1 et 4) sous les deux roues de chaque côté (colonnes 1 et 2). */
    public static Part partAt(int i, int j) {
        if (i == 0 && j == 0) return Part.ORIGIN;
        return (i == 1 || i == 2) && (j == 1 || j == 4) ? Part.ROLLER : Part.DECK;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, PART); }

    @Override @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

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

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DynoBE be) {
            Direction f = state.getValue(FACING);
            BlockPos origin = pos.relative(f, -be.bj).relative(f.getClockWise(), -be.bi);
            for (int i = 0; i < WIDTH; i++) {
                for (int j = 0; j < LENGTH; j++) {
                    BlockPos p = cell(origin, f, i, j);
                    if (p.equals(pos) || !(level.getBlockState(p).getBlock() instanceof DynoBenchBlock)) continue;
                    level.destroyBlock(p, p.equals(origin) && !player.isCreative());
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }
}
