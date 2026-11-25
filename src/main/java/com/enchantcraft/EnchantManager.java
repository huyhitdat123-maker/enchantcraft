package com.enchantcraft;

import com.enchantcraft.enchants.CustomEnchant;
import com.enchantcraft.enchants.CustomEnchant.TargetType;
import com.enchantcraft.enchants.CustomEnchant.TriggerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class EnchantManager implements Listener {

    private final EnchantCraftPlugin plugin;
    private final NamespacedKey bookEnchantKey;
    private final Map<String, CustomEnchant> enchants = new LinkedHashMap<>();
    private final List<NamespacedKey> registeredRecipes = new ArrayList<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public EnchantManager(EnchantCraftPlugin plugin) {
        this.plugin = plugin;
        this.bookEnchantKey = new NamespacedKey(plugin, "enchantcraft_book_id");
    }

    public void loadEnchants() {
        // Gỡ công thức cũ trước khi nạp lại (/ec reload)
        for (NamespacedKey key : registeredRecipes) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipes.clear();
        enchants.clear();

        File file = new File(plugin.getDataFolder(), "enchants.yml");
        if (!file.exists()) {
            plugin.saveResource("enchants.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("enchants");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(id);
            if (sec == null) continue;

            try {
                String displayName = sec.getString("display_name", id);
                TargetType target = TargetType.valueOf(sec.getString("target", "ALL").toUpperCase());
                String description = sec.getString("description", "");
                TriggerType trigger = TriggerType.valueOf(sec.getString("trigger", "ATTACK").toUpperCase());
                double chance = sec.getDouble("chance", 1.0);
                int cooldown = sec.getInt("cooldown", 0);
                List<String> actions = sec.getStringList("actions");

                List<String> shape = null;
                Map<Character, Material> ingredients = new HashMap<>();

                ConfigurationSection recipeSec = sec.getConfigurationSection("recipe");
                if (recipeSec != null) {
                    shape = recipeSec.getStringList("shape");
                    ConfigurationSection ingSec = recipeSec.getConfigurationSection("ingredients");
                    if (ingSec != null) {
                        for (String charKey : ingSec.getKeys(false)) {
                            if (!charKey.isEmpty()) {
                                Material mat = Material.matchMaterial(ingSec.getString(charKey, "AIR"));
                                if (mat != null && !mat.isAir()) {
                                    ingredients.put(charKey.charAt(0), mat);
                                }
                            }
                        }
                    }
                }

                CustomEnchant enchant = new CustomEnchant(
                        id, displayName, target, description, trigger, chance, cooldown, shape, ingredients, actions
                );
                enchants.put(enchant.getId(), enchant);

                if (shape != null && shape.size() == 3 && !ingredients.isEmpty()) {
                    NamespacedKey recipeKey = new NamespacedKey(plugin, "recipe_" + enchant.getId());
                    ShapedRecipe recipe = new ShapedRecipe(recipeKey, createEnchantBook(enchant.getId()));
                    recipe.shape(shape.get(0), shape.get(1), shape.get(2));
                    ingredients.forEach(recipe::setIngredient);
                    Bukkit.addRecipe(recipe);
                    registeredRecipes.add(recipeKey);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Loi khi doc phu phep '" + id + "' trong enchants.yml: " + e.getMessage());
            }
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
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity target) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (!weapon.getType().isAir() && weapon.hasItemMeta()) {
                triggerItemEnchants(weapon, TriggerType.ATTACK, attacker, target);
            }
        }

        if (event.getEntity() instanceof Player victim) {
            LivingEntity attacker = (event.getDamager() instanceof LivingEntity le) ? le : null;
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                    triggerItemEnchants(armor, TriggerType.DEFEND, victim, attacker);
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().isAir() && tool.hasItemMeta()) {
            triggerItemEnchants(tool, TriggerType.MINE, player, null);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        ItemStack item = event.getItem();
        if (item != null && !item.getType().isAir() && item.hasItemMeta()) {
            triggerItemEnchants(item, TriggerType.INTERACT, event.getPlayer(), null);
        }
    }

    private void triggerItemEnchants(ItemStack item, TriggerType trigger, Player self, LivingEntity target) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (CustomEnchant enchant : enchants.values()) {
            if (enchant.getTriggerType() != trigger) continue;
            if (!pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) continue;

            if (ThreadLocalRandom.current().nextDouble() > enchant.getChance()) continue;
            if (isOnCooldown(self, enchant)) continue;

            executeActions(enchant.getActions(), self, target);
        }
    }

    private boolean isOnCooldown(Player player, CustomEnchant enchant) {
        if (enchant.getCooldownSeconds() <= 0) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> playerCd = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long readyAt = playerCd.getOrDefault(enchant.getId(), 0L);
        if (now < readyAt) return true;
        playerCd.put(enchant.getId(), now + (enchant.getCooldownSeconds() * 1000L));
        return false;
    }

    private void executeActions(List<String> actions, Player self, LivingEntity target) {
        for (String raw : actions) {
            String[] parts = raw.split(":");
            String type = parts[0].toUpperCase();

            try {
                switch (type) {
                    case "HEAL" -> {
                        double amount = Double.parseDouble(parts[1]);
                        AttributeInstance attr = self.getAttribute(Attribute.MAX_HEALTH);
                        double max = (attr != null) ? attr.getValue() : 20.0;
                        self.setHealth(Math.min(max, self.getHealth() + amount));
                    }
                    case "DAMAGE" -> {
                        if (target != null) {
                            double dmg = Double.parseDouble(parts[1]);
                            target.damage(dmg);
                        }
                    }
                    case "LIGHTNING" -> {
                        if (target != null) target.getWorld().strikeLightning(target.getLocation());
                    }
                    case "LIGHTNING_EFFECT" -> {
                        if (target != null) target.getWorld().strikeLightningEffect(target.getLocation());
                    }
                    case "IGNITE" -> {
                        if (target != null) target.setFireTicks(Integer.parseInt(parts[1]));
                    }
                    case "EXPLODE" -> {
                        float power = Float.parseFloat(parts[1]);
                        if (target != null) {
                            target.getWorld().createExplosion(target.getLocation(), power, false, false);
                        } else {
                            self.getWorld().createExplosion(self.getLocation(), power, false, false);
                        }
                    }
                    case "POTION_SELF" -> applyPotion(self, parts);
                    case "POTION_TARGET" -> {
                        if (target != null) applyPotion(target, parts);
                    }
                    case "LAUNCH" -> {
                        if (target != null) {
                            double y = Double.parseDouble(parts[1]);
                            target.setVelocity(target.getVelocity().setY(y));
                        }
                    }
                    case "DASH" -> {
                        double power = Double.parseDouble(parts[1]);
                        Vector dir = self.getLocation().getDirection().normalize().multiply(power);
                        self.setVelocity(dir.setY(0.35));
                    }
                    case "SOUND" -> {
                        Sound sound = Sound.valueOf(parts[1].toUpperCase());
                        self.playSound(self.getLocation(), sound, 1.0f, 1.0f);
                    }
                    case "ACTIONBAR" -> {
                        String msg = raw.substring(raw.indexOf(':') + 1);
                        self.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void applyPotion(LivingEntity entity, String[] parts) {
        PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(parts[1].toLowerCase()));
        if (effectType != null) {
            int level = Math.max(0, Integer.parseInt(parts[2]) - 1);
            int ticks = Integer.parseInt(parts[3]);
            entity.addPotionEffect(new PotionEffect(effectType, ticks, level));
        }
    }
}
