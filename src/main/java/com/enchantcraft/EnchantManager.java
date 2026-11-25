package com.enchantcraft;

import com.enchantcraft.enchants.CustomEnchant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EnchantManager implements Listener {

    private final EnchantCraftPlugin plugin;
    private final NamespacedKey bookEnchantKey;
    private final Map<String, CustomEnchant> enchants = new LinkedHashMap<>();

    public EnchantManager(EnchantCraftPlugin plugin) {
        this.plugin = plugin;
        this.bookEnchantKey = new NamespacedKey(plugin, "enchantcraft_book_id");
    }

    public void register(CustomEnchant enchant) {
        enchants.put(enchant.getId(), enchant);

        if (enchant.getRecipeShape() != null) {
            NamespacedKey recipeKey = new NamespacedKey(plugin, "recipe_" + enchant.getId());
            if (Bukkit.getRecipe(recipeKey) != null) {
                Bukkit.removeRecipe(recipeKey);
            }
            ShapedRecipe recipe = new ShapedRecipe(recipeKey, createEnchantBook(enchant.getId()));
            recipe.shape(enchant.getRecipeShape());
            enchant.getRecipeIngredients().forEach(recipe::setIngredient);
            Bukkit.addRecipe(recipe);
        }
    }

    public CustomEnchant getEnchant(String id) {
        return enchants.get(id.toLowerCase());
    }

    public Collection<CustomEnchant> getAllEnchants() {
        return enchants.values();
    }

    private NamespacedKey getAppliedKey(String enchantId) {
        return new NamespacedKey(plugin, "applied_" + enchantId.toLowerCase());
    }

    public ItemStack createEnchantBook(String enchantId) {
        CustomEnchant enchant = getEnchant(enchantId);
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        if (enchant == null) return book;

        ItemMeta meta = book.getItemMeta();
        if (meta == null) return book;

        meta.displayName(Component.text("Sách Phù Phép: " + enchant.getDisplayName(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Áp dụng cho: " + enchant.getTargetType().getLabel(), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Hiệu ứng: " + enchant.getDescription(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("▶ Cầm sách và Chuột Phải vào trang bị trong túi đồ", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(bookEnchantKey, PersistentDataType.STRING, enchant.getId());

        book.setItemMeta(meta);
        return book;
    }

    @EventHandler
    public void onApplyBookInInventory(InventoryClickEvent event) {
        if (!event.isRightClick()) return;
        if (event.getClickedInventory() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();

        if (cursor.getType() != Material.ENCHANTED_BOOK || target == null || target.getType().isAir()) {
            return;
        }

        ItemMeta bookMeta = cursor.getItemMeta();
        if (bookMeta == null) return;

        String enchantId = bookMeta.getPersistentDataContainer().get(bookEnchantKey, PersistentDataType.STRING);
        if (enchantId == null) return;

        CustomEnchant enchant = getEnchant(enchantId);
        if (enchant == null) return;

        if (!enchant.getTargetType().matches(target.getType())) {
            player.sendMessage(Component.text("Phù phép " + enchant.getDisplayName() + " chỉ ép được vào: " + enchant.getTargetType().getLabel(), NamedTextColor.RED));
            return;
        }

        ItemMeta targetMeta = target.getItemMeta();
        if (targetMeta == null) return;

        NamespacedKey appliedKey = getAppliedKey(enchant.getId());
        PersistentDataContainer targetPdc = targetMeta.getPersistentDataContainer();
        if (targetPdc.has(appliedKey, PersistentDataType.BYTE)) {
            player.sendMessage(Component.text("Trang bị này đã có phù phép " + enchant.getDisplayName() + " rồi!", NamedTextColor.RED));
            return;
        }

        targetPdc.set(appliedKey, PersistentDataType.BYTE, (byte) 1);

        List<Component> lore = targetMeta.hasLore() && targetMeta.lore() != null
                ? new ArrayList<>(targetMeta.lore())
                : new ArrayList<>();
        lore.add(Component.text(enchant.getDisplayName(), NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        targetMeta.lore(lore);
        targetMeta.setEnchantmentGlintOverride(true);
        target.setItemMeta(targetMeta);

        event.setCancelled(true);
        if (cursor.getAmount() > 1) {
            cursor.setAmount(cursor.getAmount() - 1);
        } else {
            player.setItemOnCursor(null);
        }

        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
        player.sendMessage(Component.text("Ép thành công phù phép " + enchant.getDisplayName() + "!", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Kích hoạt khi tấn công bằng vũ khí trên tay chính
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity target) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (!weapon.getType().isAir() && weapon.hasItemMeta()) {
                PersistentDataContainer pdc = weapon.getItemMeta().getPersistentDataContainer();
                for (CustomEnchant enchant : enchants.values()) {
                    if (pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) {
                        enchant.triggerAttack(attacker, target, event);
                    }
                }
            }
        }

        // Kích hoạt khi mặc giáp bị tấn công
        if (event.getEntity() instanceof Player victim) {
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                    PersistentDataContainer pdc = armor.getItemMeta().getPersistentDataContainer();
                    for (CustomEnchant enchant : enchants.values()) {
                        if (pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) {
                            enchant.triggerDefend(victim, event);
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType().isAir() || !tool.hasItemMeta()) return;

        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        for (CustomEnchant enchant : enchants.values()) {
            if (pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) {
                enchant.triggerMine(player, event.getBlock(), event);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (CustomEnchant enchant : enchants.values()) {
            if (pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) {
                enchant.triggerInteract(event.getPlayer(), event);
            }
        }
    }
}
