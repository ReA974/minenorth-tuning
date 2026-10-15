package fr.minenorth.banc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Contenu du chariot de bouteilles : 9 emplacements qui n'acceptent que des bouteilles de nitro (affiché comme un coffre d'une rangée). */
public class NitroCartBE extends BlockEntity implements Container {
    public static final int SIZE = 9;
    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public NitroCartBE(BlockPos pos, BlockState state) {
        super(ModBanc.NITRO_CART_BE.get(), pos, state);
    }

    /** Nombre de bouteilles stockées. */
    public int bottles() {
        int n = 0;
        for (ItemStack s : items) if (s.is(ModBanc.NITRO_BOTTLE.get())) n += s.getCount();
        return n;
    }

    /** Retire {@code n} bouteilles. */
    public void take(int n) {
        for (int i = 0; i < SIZE && n > 0; i++) {
            ItemStack s = items.get(i);
            if (!s.is(ModBanc.NITRO_BOTTLE.get())) continue;
            int k = Math.min(n, s.getCount());
            s.shrink(k);
            n -= k;
        }
        setChanged();
    }

    public void dropAll(net.minecraft.world.level.Level level, BlockPos pos) {
        net.minecraft.world.Containers.dropContents(level, pos, this);
    }

    @Override public int getContainerSize() { return SIZE; }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : items) if (!s.isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack r = ContainerHelper.removeItem(items, slot, amount);
        if (!r.isEmpty()) setChanged();
        return r;
    }

    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize());
        setChanged();
    }

    @Override public boolean stillValid(Player p) {
        return level != null && level.getBlockEntity(worldPosition) == this && p.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64;
    }

    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return stack.is(ModBanc.NITRO_BOTTLE.get()); }

    @Override public void clearContent() { items.clear(); }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
    }
}
