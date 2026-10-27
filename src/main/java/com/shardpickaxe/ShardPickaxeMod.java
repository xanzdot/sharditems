package com.shardpickaxe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class ShardPickaxeMod implements ModInitializer {
	public static final String MOD_ID = "shard_pickaxe";

	/** Half-width of the mined square: 4 means -4..+4 = 9 blocks across. */
	private static final int RADIUS = 4;

	private static final RegistryKey<Item> KEY =
			RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "shard_pickaxe"));

	public static final Item SHARD_PICKAXE = Registry.register(
			Registries.ITEM,
			KEY,
			new Item(new Item.Settings()
					.registryKey(KEY)
					.fireproof()
					.pickaxe(ToolMaterial.NETHERITE, 1.0f, -2.8f))
	);

	/** Guards against the area-break re-triggering the break event for every extra block. */
	private static boolean breakingArea = false;

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(SHARD_PICKAXE));

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (breakingArea || world.isClient()) return true;
			if (!(player instanceof ServerPlayerEntity serverPlayer)) return true;
			if (!player.getMainHandStack().isOf(SHARD_PICKAXE)) return true;
			// Sneak to mine a single block like a normal pickaxe.
			if (player.isSneaking()) return true;

			mineArea(world, serverPlayer, pos);
			return true; // let the original block break normally
		});
	}

	private static void mineArea(World world, ServerPlayerEntity player, BlockPos origin) {
		HitResult hit = player.raycast(player.getBlockInteractionRange() + 1.0, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(origin)) return;
		Direction.Axis axis = blockHit.getSide().getAxis();

		breakingArea = true;
		try {
			for (int a = -RADIUS; a <= RADIUS; a++) {
				for (int b = -RADIUS; b <= RADIUS; b++) {
					if (a == 0 && b == 0) continue;

					BlockPos target = switch (axis) {
						case Y -> origin.add(a, 0, b);
						case X -> origin.add(0, a, b);
						case Z -> origin.add(a, b, 0);
					};

					ItemStack held = player.getMainHandStack();
					if (held.isEmpty() || !held.isOf(SHARD_PICKAXE)) return; // pickaxe broke

					BlockState targetState = world.getBlockState(target);
					if (targetState.isAir()) continue;
					if (targetState.getHardness(world, target) < 0) continue; // bedrock etc.
					if (!targetState.isIn(BlockTags.PICKAXE_MINEABLE)) continue;

					player.interactionManager.tryBreakBlock(target);
				}
			}
		} finally {
			breakingArea = false;
		}
	}
}
