package com.shardpickaxe;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

public class ShardPickaxeMod implements ModInitializer {
	public static final String MOD_ID = "shard_pickaxe";

	/** Half-width of the pickaxe square: 1 means 3x3 = 9 blocks. */
	private static final int PICK_RADIUS = 1;
	/** Safety limits for the axe's tree-felling. */
	private static final int MAX_TREE_BLOCKS = 800;
	private static final int TREE_RADIUS = 24;
	/** Ticks within which a second strike counts against enchanted netherite armor (5 seconds). */
	private static final long ARMOR_STRIKE_WINDOW = 100;

	/** Netherite with Efficiency V (+26 speed) and Unbreaking III (~4x durability) built in. */
	private static final ToolMaterial SHARD_MATERIAL = new ToolMaterial(
			BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 8124, 35.0f, 4.0f, 15, ItemTags.NETHERITE_TOOL_MATERIALS);
	/** Speed high enough that logs break instantly. */
	private static final ToolMaterial AXE_MATERIAL = new ToolMaterial(
			BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 8124, 100.0f, 4.0f, 15, ItemTags.NETHERITE_TOOL_MATERIALS);

	public static final Item SHARD_PICKAXE = register("shard_pickaxe", s -> s
			.pickaxe(SHARD_MATERIAL, 1.0f, -2.8f)
			.component(DataComponentTypes.TOOL, anyBlockTool()));
	public static final Item SHARD_AXE = register("shard_axe", s -> s.axe(AXE_MATERIAL, 5.0f, -3.0f));
	public static final Item SHARD_SWORD = register("shard_sword", s -> s.sword(SHARD_MATERIAL, 3.0f, -2.4f));

	/** Guards against area-breaks re-triggering the break event for every extra block. */
	private static boolean breakingArea = false;
	/** Last strike tick per target, for the sword's two-hit rule on enchanted netherite armor. */
	private static final Map<UUID, Long> lastStrike = new HashMap<>();

	private static Item register(String name, UnaryOperator<Item.Settings> config) {
		RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, name));
		Item.Settings settings = config.apply(new Item.Settings().registryKey(key).fireproof());
		return Registry.register(Registries.ITEM, key, new Item(settings));
	}

	/** Mines every block at full speed; pickaxe-mineable blocks also drop their items. */
	private static ToolComponent anyBlockTool() {
		return new ToolComponent(
				List.of(ToolComponent.Rule.ofAlwaysDropping(Registries.BLOCK.getOrThrow(BlockTags.PICKAXE_MINEABLE), 35.0f)),
				35.0f, 1, true);
	}

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
			entries.add(SHARD_PICKAXE);
			entries.add(SHARD_AXE);
		});
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(SHARD_SWORD));

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (breakingArea || world.isClient()) return true;
			if (!(player instanceof ServerPlayerEntity serverPlayer)) return true;
			if (player.isSneaking()) return true; // sneak = normal single-block break

			ItemStack held = player.getMainHandStack();
			if (held.isOf(SHARD_PICKAXE)) {
				mineArea(world, serverPlayer, pos);
			} else if (held.isOf(SHARD_AXE) && state.isIn(BlockTags.LOGS)) {
				fellTree(world, serverPlayer, pos);
			}
			return true; // let the original block break normally
		});

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (breakingArea) return;
			if (!(source.getAttacker() instanceof ServerPlayerEntity attacker)) return;
			if (source.getSource() != attacker) return; // melee only
			if (!attacker.getMainHandStack().isOf(SHARD_SWORD)) return;
			if (!hasTotem(entity)) return;
			if (!entity.isAlive()) return;

			ServerWorld world = attacker.getEntityWorld();

			if (hasEnchantedNetheriteArmor(entity)) {
				long now = world.getTime();
				Long prev = lastStrike.get(entity.getUuid());
				if (prev == null || now - prev > ARMOR_STRIKE_WINDOW) {
					lastStrike.put(entity.getUuid(), now); // first strike: just a normal hit
					return;
				}
				lastStrike.remove(entity.getUuid()); // second strike: pop
			}

			// Lethal magic damage makes the totem trigger right now.
			breakingArea = true;
			try {
				entity.damage(world, world.getDamageSources().magic(), Float.MAX_VALUE);
			} finally {
				breakingArea = false;
			}
		});
	}

	private static boolean hasTotem(LivingEntity entity) {
		return entity.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)
				|| entity.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
	}

	private static boolean hasEnchantedNetheriteArmor(LivingEntity entity) {
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (EquipmentSlot slot : slots) {
			ItemStack stack = entity.getEquippedStack(slot);
			if (stack.isEmpty() || !stack.hasEnchantments()) continue;
			if (Registries.ITEM.getId(stack.getItem()).getPath().startsWith("netherite_")) return true;
		}
		return false;
	}

	/** True for blocks the area tools may break (anything breakable that isn't a fluid). */
	private static boolean breakable(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || state.getBlock() instanceof FluidBlock) return false;
		return state.getHardness(world, pos) >= 0; // skips bedrock etc.
	}

	private static void mineArea(World world, ServerPlayerEntity player, BlockPos origin) {
		HitResult hit = player.raycast(player.getBlockInteractionRange() + 1.0, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(origin)) return;
		Direction.Axis axis = blockHit.getSide().getAxis();

		breakingArea = true;
		try {
			for (int a = -PICK_RADIUS; a <= PICK_RADIUS; a++) {
				for (int b = -PICK_RADIUS; b <= PICK_RADIUS; b++) {
					if (a == 0 && b == 0) continue;
					BlockPos target = switch (axis) {
						case Y -> origin.add(a, 0, b);
						case X -> origin.add(0, a, b);
						case Z -> origin.add(a, b, 0);
					};
					ItemStack held = player.getMainHandStack();
					if (held.isEmpty() || !held.isOf(SHARD_PICKAXE)) return; // pickaxe broke
					if (!breakable(world, target)) continue;
					player.interactionManager.tryBreakBlock(target);
				}
			}
		} finally {
			breakingArea = false;
		}
	}

	private static void fellTree(World world, ServerPlayerEntity player, BlockPos origin) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		List<BlockPos> found = new ArrayList<>();
		queue.add(origin);
		seen.add(origin);

		while (!queue.isEmpty() && found.size() < MAX_TREE_BLOCKS) {
			BlockPos p = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = p.add(dx, dy, dz);
						if (!seen.add(n)) continue;
						if (Math.abs(n.getX() - origin.getX()) > TREE_RADIUS
								|| Math.abs(n.getZ() - origin.getZ()) > TREE_RADIUS
								|| Math.abs(n.getY() - origin.getY()) > TREE_RADIUS * 2) continue;
						BlockState s = world.getBlockState(n);
						if (s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES)) {
							queue.add(n);
							found.add(n);
						}
					}
				}
			}
		}

		breakingArea = true;
		try {
			for (BlockPos n : found) {
				ItemStack held = player.getMainHandStack();
				if (held.isEmpty() || !held.isOf(SHARD_AXE)) return; // axe broke
				if (!breakable(world, n)) continue;
				player.interactionManager.tryBreakBlock(n);
			}
		} finally {
			breakingArea = false;
		}
	}
}
