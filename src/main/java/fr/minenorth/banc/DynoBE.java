package fr.minenorth.banc;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Banc de puissance rattaché à une entreprise (identifiant de l'entreprise du mod Entreprises). */
public class DynoBE extends BlockEntity {
    /** Entreprise propriétaire, -1 si aucune (banc inerte). */
    public int companyId = -1;
    /** Case du banc dynamométrique (0..3 sur la largeur, 0..7 sur la longueur) ; 0, 0 pour le banc simple. */
    public int bi, bj;

    public DynoBE(BlockPos pos, BlockState state) {
        super(ModBanc.DYNO_BE.get(), pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("company", companyId);
        tag.putInt("bi", bi);
        tag.putInt("bj", bj);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        companyId = tag.contains("company") ? tag.getInt("company") : -1;
        bi = tag.getInt("bi");
        bj = tag.getInt("bj");
    }
}
