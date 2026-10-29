package com.emeraldtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.ToolComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EmeraldToolsPlugin extends JavaPlugin implements Listener, TabExecutor {

	/** Half-width of the pickaxe square: 1 means 3x3 = 9 blocks. */
	private static final int PICK_RADIUS = 1;
	private static final int MAX_TREE_BLOCKS = 800;
	private static final int TREE_RADIUS = 24;
	/** Ticks within which a second strike counts against enchanted netherite armor (5 seconds). */
	private static final long ARMOR_STRIKE_WINDOW = 100;

	enum Kind {
		PICKAXE("emerald_pickaxe", "Emerald Pickaxe", Material.NETHERITE_PICKAXE, new String[]{"EEE", " B ", " B "}),
		AXE("emerald_axe", "Emerald Axe", Material.NETHERITE_AXE, new String[]{"EE", "EB", " B"}),
		SWORD("emerald_sword", "Emerald Sword", Material.NETHERITE_SWORD, new String[]{"E", "E", "B"});

		final String id;
		final String display;
		final Material base;
		final String[] shape;

		Kind(String id, String display, Material base, String[] shape) {
			this.id = id;
			this.display = display;
			this.base = base;
			this.shape = shape;
		}
	}

	private NamespacedKey typeKey;
	/** Guards against area-breaks re-triggering our own break handler. */
	private boolean busy = false;
	private final Map<UUID, Long> lastStrike = new HashMap<>();

	@Override
	public void onEnable() {
		typeKey = new NamespacedKey(this, "tool");
		Bukkit.getPluginManager().registerEvents(this, this);
		var cmd = getCommand("emeraldtools");
		if (cmd != null) {
			cmd.setExecutor(this);
			cmd.setTabCompleter(this);
		}
		for (Kind k : Kind.values()) {
			NamespacedKey key = new NamespacedKey(this, k.id);
			Bukkit.removeRecipe(key);
			ShapedRecipe recipe = new ShapedRecipe(key, createItem(k));
			recipe.shape(k.shape);
			recipe.setIngredient('E', new RecipeChoice.MaterialChoice(Material.DEEPSLATE_EMERALD_ORE));
			recipe.setIngredient('B', new RecipeChoice.MaterialChoice(Material.BREEZE_ROD));
			Bukkit.addRecipe(recipe);
		}
	}

	// ---------------------------------------------------------------- items

	private ItemStack createItem(Kind kind) {
		ItemStack item = new ItemStack(kind.base);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(Component.text(kind.display, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
		meta.setItemModel(NamespacedKey.fromString("emerald_tools:" + kind.id));
		meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, kind.id);
		// Maxed-out netherite: Unbreaking III on everything, Efficiency on the tools.
		meta.addEnchant(Enchantment.UNBREAKING, 3, true);
		if (kind == Kind.PICKAXE) {
			meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
			// Mines every block quickly, not just pickaxe blocks.
			ToolComponent tool = meta.getTool();
			tool.setDefaultMiningSpeed(35.0f);
			tool.addRule(Tag.MINEABLE_PICKAXE, 35.0f, true);
			meta.setTool(tool);
		} else if (kind == Kind.AXE) {
			meta.addEnchant(Enchantment.EFFICIENCY, 10, true);
		}
		item.setItemMeta(meta);
		return item;
	}

	private Kind kindOf(ItemStack item) {
		if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
		String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
		if (id == null) return null;
		for (Kind k : Kind.values()) if (k.id.equals(id)) return k;
		return null;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		List<NamespacedKey> keys = new ArrayList<>();
		for (Kind k : Kind.values()) keys.add(new NamespacedKey(this, k.id));
		e.getPlayer().discoverRecipes(keys);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		lastStrike.remove(e.getPlayer().getUniqueId());
	}

	// ------------------------------------------------------- pickaxe & axe

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		if (busy) return;
		Player p = e.getPlayer();
		if (p.isSneaking()) return; // sneak = normal single-block break
		Kind kind = kindOf(p.getInventory().getItemInMainHand());
		if (kind == Kind.PICKAXE) {
			mineArea(p, e.getBlock());
		} else if (kind == Kind.AXE && Tag.LOGS.isTagged(e.getBlock().getType())) {
			fellTree(p, e.getBlock());
		}
	}

	private boolean breakable(Block b) {
		Material m = b.getType();
		return !m.isAir() && !b.isLiquid() && m.getHardness() >= 0; // skips bedrock etc.
	}

	private boolean stillHolding(Player p, Kind kind) {
		return kindOf(p.getInventory().getItemInMainHand()) == kind;
	}

	private void breakAs(Player p, Block b) {
		busy = true;
		try {
			p.breakBlock(b);
		} finally {
			busy = false;
		}
	}

	private void mineArea(Player p, Block origin) {
		RayTraceResult hit = p.rayTraceBlocks(7.0);
		if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) return;
		if (!hit.getHitBlock().equals(origin)) return;
		BlockFace face = hit.getHitBlockFace();

		for (int a = -PICK_RADIUS; a <= PICK_RADIUS; a++) {
			for (int b = -PICK_RADIUS; b <= PICK_RADIUS; b++) {
				if (a == 0 && b == 0) continue;
				Block target;
				switch (face) {
					case UP, DOWN -> target = origin.getRelative(a, 0, b);
					case EAST, WEST -> target = origin.getRelative(0, a, b);
					default -> target = origin.getRelative(a, b, 0);
				}
				if (!stillHolding(p, Kind.PICKAXE)) return; // pickaxe broke
				if (!breakable(target)) continue;
				breakAs(p, target);
			}
		}
	}

	private void fellTree(Player p, Block origin) {
		ArrayDeque<Block> queue = new ArrayDeque<>();
		Set<Block> seen = new HashSet<>();
		List<Block> found = new ArrayList<>();
		queue.add(origin);
		seen.add(origin);

		while (!queue.isEmpty() && found.size() < MAX_TREE_BLOCKS) {
			Block cur = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						Block n = cur.getRelative(dx, dy, dz);
						if (!seen.add(n)) continue;
						if (Math.abs(n.getX() - origin.getX()) > TREE_RADIUS
								|| Math.abs(n.getZ() - origin.getZ()) > TREE_RADIUS
								|| Math.abs(n.getY() - origin.getY()) > TREE_RADIUS * 2) continue;
						Material m = n.getType();
						if (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m)) {
							queue.add(n);
							found.add(n);
						}
					}
				}
			}
		}

		for (Block n : found) {
			if (!stillHolding(p, Kind.AXE)) return; // axe broke
			breakAs(p, n);
		}
	}

	// --------------------------------------------------------------- sword

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onHit(EntityDamageByEntityEvent e) {
		if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
		if (!(e.getDamager() instanceof Player attacker)) return;
		if (!(e.getEntity() instanceof Player victim)) return;
		if (kindOf(attacker.getInventory().getItemInMainHand()) != Kind.SWORD) return;
		if (victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) return;
		if (!hasTotem(victim)) return;

		if (hasEnchantedNetheriteArmor(victim)) {
			long now = victim.getWorld().getGameTime();
			Long prev = lastStrike.get(victim.getUniqueId());
			if (prev == null || now - prev > ARMOR_STRIKE_WINDOW) {
				lastStrike.put(victim.getUniqueId(), now); // first strike: normal hit
				return;
			}
			lastStrike.remove(victim.getUniqueId()); // second strike: pop
		}
		// Lethal damage makes the totem trigger right now.
		e.setDamage(1000.0);
	}

	private boolean hasTotem(Player p) {
		return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
				|| p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
	}

	private boolean hasEnchantedNetheriteArmor(Player p) {
		for (ItemStack a : p.getInventory().getArmorContents()) {
			if (a == null || a.getType().isAir()) continue;
			if (a.getType().name().startsWith("NETHERITE_") && !a.getEnchantments().isEmpty()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------- command

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
			sender.sendMessage("Usage: /" + label + " give <pickaxe|axe|sword> [player]");
			return true;
		}
		Kind kind;
		try {
			kind = Kind.valueOf(args[1].toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			sender.sendMessage("Unknown tool. Choose pickaxe, axe or sword.");
			return true;
		}
		Player target = null;
		if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
		else if (sender instanceof Player self) target = self;
		if (target == null) {
			sender.sendMessage("Player not found.");
			return true;
		}
		target.getInventory().addItem(createItem(kind)).values()
				.forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
		sender.sendMessage("Gave " + kind.display + " to " + target.getName() + ".");
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return List.of("give");
		if (args.length == 2) return List.of("pickaxe", "axe", "sword");
		if (args.length == 3) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		return List.of();
	}
}
