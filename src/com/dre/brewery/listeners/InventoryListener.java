package com.dre.brewery.listeners;

import com.dre.brewery.BPlayer;
import com.dre.brewery.BRecipe;
import com.dre.brewery.Barrel;
import com.dre.brewery.Brew;
import com.dre.brewery.MCBarrel;
import com.dre.brewery.P;

import org.bukkit.Bukkit;
import com.dre.brewery.*;
import com.dre.brewery.filedata.BConfig;
import com.dre.brewery.lore.BrewLore;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.HashSet;
import java.util.UUID;

public class InventoryListener implements Listener {

	/* === Recreating manually the prior BrewEvent behavior. === */
	private HashSet<UUID> trackedBrewmen = new HashSet<>();

	/**
	 * Start tracking distillation for a person when they open the brewer window.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBrewerOpen(InventoryOpenEvent event) {
		if (!P.use1_9) return;
		HumanEntity player = event.getPlayer();
		Inventory inv = event.getInventory();
		if (player == null || !(inv instanceof BrewerInventory)) return;

		P.p.debugLog("Starting brew inventory tracking");
		trackedBrewmen.add(player.getUniqueId());
	}

	/**
	 * Stop tracking distillation for a person when they close the brewer window.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBrewerClose(InventoryCloseEvent event) {
		if (!P.use1_9) return;
		HumanEntity player = event.getPlayer();
		Inventory inv = event.getInventory();
		if (player == null || !(inv instanceof BrewerInventory)) return;

		P.p.debugLog("Stopping brew inventory tracking");
		trackedBrewmen.remove(player.getUniqueId());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBrewerDrag(InventoryDragEvent event) {
		if (!P.use1_9) return;
		// Workaround the Drag event when only clicking a slot
		if (event.getInventory() instanceof BrewerInventory) {
			onBrewerClick(new InventoryClickEvent(event.getView(), InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL));
		}
	}

	/**
	 * Clicking can either start or stop the new brew distillation tracking.
	 * <p>Note that server restart will halt any ongoing brewing processes and
	 * they will _not_ restart until a new click event.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBrewerClick(InventoryClickEvent event) {
		if (!P.use1_9) return;

		HumanEntity player = event.getWhoClicked();
		Inventory inv = event.getInventory();
		if (player == null || !(inv instanceof BrewerInventory)) return;

		UUID puid = player.getUniqueId();
		if (!trackedBrewmen.contains(puid)) return;

		if (InventoryType.BREWING != inv.getType()) return;
		if (event.getAction() == InventoryAction.NOTHING) return; // Ignore clicks that do nothing

		BrewerInventory brewer = (BrewerInventory) inv;
		final Block brewery = brewer.getHolder().getBlock();

		// If we were already tracking the brewer, cancel any ongoing event due to the click.
		Integer curTask = trackedBrewers.get(brewery);
		if (curTask != null) {
			Bukkit.getScheduler().cancelTask(curTask); // cancel prior
			brewer.getHolder().setBrewingTime(0); // Fixes brewing continuing without fuel for normal potions
			brewer.getHolder().update();
		}
		final int fuel = brewer.getHolder().getFuelLevel();

		// Now check if we should bother to track it.
		trackedBrewers.put(brewery, new BukkitRunnable() {
			private int runTime = -1;
			private int brewTime = -1;
			@Override
			public void run() {
				BlockState now = brewery.getState();
				if (now instanceof BrewingStand) {
					BrewingStand stand = (BrewingStand) now;
					if (brewTime == -1) { // only check at the beginning (and end) for distillables
						switch (hasCustom(stand.getInventory())) {
							case 1:
								// Custom potion but not for distilling. Stop any brewing and cancel this task
								if (stand.getBrewingTime() > 0) {
									if (P.use1_11) {
										// The trick below doesnt work in 1.11, but we dont need it anymore
										// This should only happen with older Brews that have been made with the old Potion Color System
										stand.setBrewingTime(Short.MAX_VALUE);
									} else {
										// Brewing time is sent and stored as short
										// This sends a negative short value to the Client
										// In the client the Brewer will look like it is not doing anything
										stand.setBrewingTime(Short.MAX_VALUE << 1);
									}
									stand.setFuelLevel(fuel);
									stand.update();
								}
							case 0:
								// No custom potion, cancel and ignore
								this.cancel();
								trackedBrewers.remove(brewery);
								P.p.debugLog("nothing to distill");
								return;
							default:
								runTime = getLongestDistillTime(stand.getInventory());
								brewTime = runTime;
								P.p.debugLog("using brewtime: " + runTime);

						}
					}

					brewTime--; // count down.
					stand.setBrewingTime((int) (brewTime / ((float) runTime / (float) DISTILLTIME)) + 1);

					if (brewTime <= 1) { // Done!
						stand.setBrewingTime(0);
						stand.update();
						BrewerInventory brewer = stand.getInventory();
						if (!runDistill(brewer)) {
							this.cancel();
							trackedBrewers.remove(brewery);
							P.p.debugLog("All done distilling");
						} else {
							brewTime = -1; // go again.
							P.p.debugLog("Can distill more! Continuing.");
						}
					} else {
						stand.update();
					}
				} else {
					this.cancel();
					trackedBrewers.remove(brewery);
					P.p.debugLog("The block was replaced; not a brewing stand.");
				}
			}
		}.runTaskTimer(P.p, 2L, 1L).getTaskId());
	}

	// Returns a Brew or null for every Slot in the BrewerInventory
	private Brew[] getDistillContents(BrewerInventory inv) {
		ItemStack item;
		Brew[] contents = new Brew[3];
		for (int slot = 0; slot < 3; slot++) {
			item = inv.getItem(slot);
			if (item != null) {
				contents[slot] = Brew.get(item);
			}
		}
		return contents;
	}

	private byte hasCustom(BrewerInventory brewer) {
		ItemStack item = brewer.getItem(3); // ingredient
		boolean glowstone = (item != null && Material.GLOWSTONE_DUST == item.getType()); // need dust in the top slot.
		byte customFound = 0;
		for (Brew brew : getDistillContents(brewer)) {
			if (brew != null) {
				if (!glowstone) {
					return 1;
				}
				if (brew.canDistill()) {
					return 2;
				} else {
					customFound = 1;
				}
			}
		}
		return customFound;
		BDistiller.distillerClick(event);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBrew(BrewEvent event) {
		if (P.use1_9) {
			if (BDistiller.hasBrew(event.getContents(), BDistiller.getDistillContents(event.getContents())) != 0) {
				event.setCancelled(true);
			}
			return;
		}
		if (BDistiller.runDistill(event.getContents(), BDistiller.getDistillContents(event.getContents()))) {
			event.setCancelled(true);
		}
	}

	// Clicked a Brew somewhere, do some updating
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
	public void onInventoryClickLow(InventoryClickEvent event) {
		if (event.getCurrentItem() != null && event.getCurrentItem().getType().equals(Material.POTION)) {
			ItemStack item = event.getCurrentItem();
			if (item.hasItemMeta()) {
				PotionMeta potion = ((PotionMeta) item.getItemMeta());
				assert potion != null;
				if (P.use1_11) {
					// Convert potions from 1.10 to 1.11 for new color
					if (potion.getColor() == null) {
						Brew brew = Brew.get(potion);
						if (brew != null) {
							brew.convertPre1_11(item);
						}
					}
				} else {
					// convert potions from 1.8 to 1.9 for color and to remove effect descriptions
					if (P.use1_9 && !potion.hasItemFlag(ItemFlag.HIDE_POTION_EFFECTS)) {
						Brew brew = Brew.get(potion);
						if (brew != null) {
							brew.convertPre1_9(item);
						}
					}
				}
				/*Brew brew = Brew.get(item);
				if (brew != null) {
					brew.touch();
				}*/
			}
		}
	}

	// convert to non colored Lore when taking out of Barrel/Brewer
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent event) {
		if (event.getInventory().getType() == InventoryType.BREWING) {
			if (event.getSlot() > 2) {
				return;
			}
		} else if (!(event.getInventory().getHolder() instanceof Barrel) && !(P.use1_14 && event.getInventory().getHolder() instanceof org.bukkit.block.Barrel)) {
			return;
		}

		ItemStack item = event.getCurrentItem();
		if (item != null && item.getType() == Material.POTION && item.hasItemMeta()) {
			PotionMeta meta = (PotionMeta) item.getItemMeta();
			assert meta != null;
			Brew brew = Brew.get(meta);
			if (brew != null) {
				BrewLore lore = null;
				if (BrewLore.hasColorLore(meta)) {
					lore = new BrewLore(brew, meta);
					lore.convertLore(false);
				} else if (!BConfig.alwaysShowAlc && event.getInventory().getType() == InventoryType.BREWING) {
					lore = new BrewLore(brew, meta);
					lore.updateAlc(false);
				}
				if (lore != null) {
					lore.write();
					item.setItemMeta(meta);
					if (event.getWhoClicked() instanceof Player) {
						switch (event.getAction()) {
							case MOVE_TO_OTHER_INVENTORY:
							case HOTBAR_SWAP:
								// Fix a Graphical glitch of item still showing colors until clicking it
								P.p.getServer().getScheduler().runTask(P.p, () -> ((Player) event.getWhoClicked()).updateInventory());
						}
					}
				}
			}
		}
	}

	// Check if the player tries to add more than the allowed amount of brews into an mc-barrel
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInventoryClickMCBarrel(InventoryClickEvent event) {
		if (!P.use1_14) return;
		if (event.getInventory().getType() != InventoryType.BARREL) return;
		if (!MCBarrel.enableAging) return;

		Inventory inv = event.getInventory();
		for (MCBarrel barrel : MCBarrel.openBarrels) {
			if (barrel.getInventory().equals(inv)) {
				barrel.clickInv(event);
				return;
			}
		}
		MCBarrel barrel = new MCBarrel(inv);
		MCBarrel.openBarrels.add(barrel);
		barrel.clickInv(event);
	}

	// Handle the Brew Sealer Inventory
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInventoryClickBSealer(InventoryClickEvent event) {
		if (!P.use1_13) return;
		InventoryHolder holder = event.getInventory().getHolder();
		if (!(holder instanceof BSealer)) {
			return;
		}
		((BSealer) holder).clickInv();
	}

	//public static boolean opening = false;

	@SuppressWarnings("deprecation")
	@EventHandler(ignoreCancelled = false)
	public void onInventoryOpenLegacyConvert(InventoryOpenEvent event) {
		if (Brew.noLegacy()) {
			return;
		}
		if (event.getInventory().getType() == InventoryType.PLAYER) {
			return;
		}
		for (ItemStack item : event.getInventory().getContents()) {
			if (item != null && item.getType() == Material.POTION) {
				int uid = Brew.getUID(item);
				// Check if the uid exists first, otherwise it will log that it can't find the id
				if (uid < 0 && Brew.legacyPotions.containsKey(uid)) {
					// This will convert the Brew
					Brew.get(item);
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInventoryOpen(InventoryOpenEvent event) {
		if (!P.use1_14) return;
		if (!MCBarrel.enableAging) return;

		/*Barrel x = null;
		if (event.getInventory().getHolder() instanceof Barrel) {
			x = ((Barrel) event.getInventory().getHolder());
		}

		if (!opening) {
			opening = true;
			Barrel finalBarrel = x;
			P.p.getServer().getScheduler().scheduleSyncDelayedTask(P.p, () -> {finalBarrel.remove(null, null); opening = false;}, 100);
		}*/

		// Check for MC Barrel
		if (event.getInventory().getType() == InventoryType.BARREL) {
			Inventory inv = event.getInventory();
			for (MCBarrel barrel : MCBarrel.openBarrels) {
				if (barrel.getInventory().equals(inv)) {
					barrel.open();
					return;
				}
			}
			MCBarrel barrel = new MCBarrel(inv);
			MCBarrel.openBarrels.add(barrel);
			barrel.open();
		}
	}

	// block the pickup of items where getPickupDelay is > 1000 (puke)
	@EventHandler(ignoreCancelled = true)
	public void onHopperPickupPuke(InventoryPickupItemEvent event){
		if (event.getItem().getPickupDelay() > 1000 && event.getItem().getItemStack().getType() == BConfig.pukeItem) {
			event.setCancelled(true);
		}
	}

	// Block taking out items from running distillers,
	// Convert Color Lore from MC Barrels back into normal color on taking out
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	public void onHopperMove(InventoryMoveItemEvent event){
		if (event.getSource() instanceof BrewerInventory) {
			if (BDistiller.isTrackingDistiller(((BrewerInventory) event.getSource()).getHolder().getBlock())) {
				event.setCancelled(true);
			}
			return;
		}

		if (!P.use1_14) return;

		if (event.getSource().getType() == InventoryType.BARREL) {
			ItemStack item = event.getItem();
			if (item.getType() == Material.POTION && Brew.isBrew(item)) {
				PotionMeta meta = (PotionMeta) item.getItemMeta();
				assert meta != null;
				if (BrewLore.hasColorLore(meta)) {
					// has color lore, convert lore back to normal
					Brew brew = Brew.get(meta);
					if (brew != null) {
						BrewLore lore = new BrewLore(brew, meta);
						lore.convertLore(false);
						lore.write();
						item.setItemMeta(meta);
						event.setItem(item);
					}
				}
			}
		}
	}

	@EventHandler
	public void onInventoryClose(InventoryCloseEvent event) {
		if (!P.use1_13) return;
		if (event.getInventory().getHolder() instanceof BSealer) {
			((BSealer) event.getInventory().getHolder()).closeInv();
		}

		if (!P.use1_14) return;

		// Barrel Closing Sound
		if (event.getInventory().getHolder() instanceof Barrel) {
			Barrel barrel = ((Barrel) event.getInventory().getHolder());
			barrel.playClosingSound();
		}

		// Check for MC Barrel
		if (MCBarrel.enableAging && event.getInventory().getType() == InventoryType.BARREL) {
			Inventory inv = event.getInventory();
			for (Iterator<MCBarrel> iter = MCBarrel.openBarrels.iterator(); iter.hasNext(); ) {
				MCBarrel barrel = iter.next();
				if (barrel.getInventory().equals(inv)) {
					barrel.close();
					if (inv.getViewers().size() == 1) {
						// Last viewer, remove Barrel from List of open Barrels
						iter.remove();
					}
					return;
				}
			}
			new MCBarrel(inv).close();
		}
	}
}
