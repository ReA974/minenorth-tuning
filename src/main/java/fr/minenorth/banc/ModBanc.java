package fr.minenorth.banc;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Enregistrements du banc de puissance (bloc, entité de bloc, objet). */
public final class ModBanc {
    private ModBanc() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MineNorthBanc.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthBanc.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MineNorthBanc.MOD_ID);

    public static final RegistryObject<Block> DYNO = BLOCKS.register("banc_puissance", DynoBlock::new);
    public static final RegistryObject<Item> DYNO_ITEM = ITEMS.register("banc_puissance", () -> new DynoItem(DYNO.get(), new Item.Properties()));
    /** Banc dynamométrique 8 x 4 (32 cases, une entité de bloc par case pour l'entreprise et la position). */
    public static final RegistryObject<Block> BENCH = BLOCKS.register("banc_dyno", DynoBenchBlock::new);
    public static final RegistryObject<Item> BENCH_ITEM = ITEMS.register("banc_dyno", () -> new DynoBenchItem(BENCH.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<DynoBE>> DYNO_BE = BLOCK_ENTITIES.register("banc_puissance",
            () -> BlockEntityType.Builder.of(DynoBE::new, DYNO.get(), BENCH.get()).build(null));

    // ---- nitro
    /** Bouteille de nitro : +15 s de réserve (clic droit sur un véhicule équipé, ou rangée dans un chariot). */
    public static final RegistryObject<Item> NITRO_BOTTLE = ITEMS.register("nitro_bottle", () -> new Item(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Block> NITRO_CART = BLOCKS.register("nitro_cart", NitroCartBlock::new);
    public static final RegistryObject<Item> NITRO_CART_ITEM = ITEMS.register("nitro_cart", () -> new BlockItem(NITRO_CART.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<NitroCartBE>> NITRO_CART_BE = BLOCK_ENTITIES.register("nitro_cart",
            () -> BlockEntityType.Builder.of(NitroCartBE::new, NITRO_CART.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
