package com.enchantcraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class EnchantManager implements Listener {

    private final NamespacedKey bookEnchantKey;
    private final NamespacedKey hutMauAppliedKey;

    public EnchantManager(EnchantCraftPlugin plugin) {
        this.bookEnchantKey = new NamespacedKey(plugin, "enchantcraft_book_id");
        this.hutMauAppliedKey = new NamespacedKey(plugin, "applied_hut_mau");
    }

    /**
     * Tạo Sách Phù Phép chuẩn PDC dùng chung cho bàn chế tạo và lệnh /ec
     */
    public ItemStack createEnchantBook(String enchantId) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta == null) return book;

        if ("hut_mau".equals(enchantId)) {
            meta.displayName(Component.text("Sách Phù Phép: Hút Máu I", NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Áp dụng cho: Kiếm", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Hiệu ứng: 30% tỉ lệ hồi 2 máu khi chém trúng", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("▶ Cầm sách và Chuột Phải vào Kiếm trong túi đồ", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(bookEnchantKey, PersistentDataType.STRING, "hut_mau");
        }

        book.setItemMeta(meta);
        return book;
    }

    /**
     * Cơ chế cầm sách chuột phải thả trực tiếp vào trang bị trong túi đồ
     */
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

        if ("hut_mau".equals(enchantId)) {
            if (!target.getType().name().endsWith("_SWORD")) {
                player.sendMessage(Component.text("Phù phép Hút Máu chỉ có thể ép vào Kiếm!", NamedTextColor.RED));
                return;
            }

            ItemMeta targetMeta = target.getItemMeta();
            if (targetMeta == null) return;

            PersistentDataContainer targetPdc = targetMeta.getPersistentDataContainer();
            if (targetPdc.has(hutMauAppliedKey, PersistentDataType.BYTE)) {
                player.sendMessage(Component.text("Vũ khí này đã có phù phép Hút Máu rồi!", NamedTextColor.RED));
                return;
            }

            targetPdc.set(hutMauAppliedKey, PersistentDataType.BYTE, (byte) 1);

            List<Component> lore = targetMeta.hasLore() && targetMeta.lore() != null
                    ? new ArrayList<>(targetMeta.lore())
                    : new ArrayList<>();
            lore.add(Component.text("Hút Máu I", NamedTextColor.RED)
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
            player.sendMessage(Component.text("Ép thành công phù phép Hút Máu I!", NamedTextColor.GREEN));
        }
    }

    /**
     * Kích hoạt hiệu ứng phù phép khi chiến đấu
     */
    @EventHandler
    public void onCombatTrigger(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon.getType().isAir() || !weapon.hasItemMeta()) return;

        PersistentDataContainer pdc = weapon.getItemMeta().getPersistentDataContainer();

        if (pdc.has(hutMauAppliedKey, PersistentDataType.BYTE)) {
            if (ThreadLocalRandom.current().nextDouble() <= 0.30) {
                AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = (maxHealthAttr != null) ? maxHealthAttr.getValue() : 20.0;
                double newHealth = Math.min(maxHealth, player.getHealth() + 2.0);
                player.setHealth(newHealth);
            }
        }
    }
}
