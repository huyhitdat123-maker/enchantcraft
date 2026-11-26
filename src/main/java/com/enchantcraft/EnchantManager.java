package com.enchantcraft;

import com.enchantcraft.enchants.CustomEnchant;
import com.enchantcraft.enchants.CustomEnchant.TargetType;
import com.enchantcraft.enchants.CustomEnchant.TriggerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class EnchantManager implements Listener {

    private static final int MAX_ENCHANTS_PER_ITEM = 6;

    private static class RandomGuiHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(null, 27);
        }
    }

    private static class RandomTier {
        final String id;
        final String displayName;
        final Material icon;
        final int slot;
        final int costLevels;
        final int salvageLevels;
        final List<String> salvageItems;
        final boolean broadcast;
        final List<String> lore;
        final List<String> pool;

        RandomTier(String id, String displayName, Material icon, int slot, int costLevels, int salvageLevels, List<String> salvageItems, boolean broadcast, List<String> lore, List<String> pool) {
            this.id = id;
            this.displayName = displayName;
            this.icon = icon;
            this.slot = slot;
            this.costLevels = costLevels;
            this.salvageLevels = salvageLevels;
            this.salvageItems = salvageItems;
            this.broadcast = broadcast;
            this.lore = lore;
            this.pool = pool;
        }
    }

    private static final Set<PotionEffectType> NEGATIVE_EFFECTS = Set.of(
            PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS, PotionEffectType.NAUSEA, PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION, PotionEffectType.UNLUCK, PotionEffectType.BAD_OMEN
    );

    private final EnchantCraftPlugin plugin;
    private final NamespacedKey bookEnchantKey;
    private final NamespacedKey bookLevelKey;
    private final NamespacedKey arrowEnchantsKey;
    private final NamespacedKey guiTierKey;
    private final NamespacedKey gaiaHpModifierKey;
    private final NamespacedKey villagerDoubledKey;
    private final NamespacedKey unreflectableFireballKey;

    private final Map<String, CustomEnchant> enchants = new LinkedHashMap<>();
    private final Map<String, RandomTier> randomTiers = new LinkedHashMap<>();
    private final Map<String, String> enchantToTierId = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, BlockFace> lastClickedFace = new HashMap<>();
    private final Map<UUID, Map<PotionEffectType, Integer>> boostedBuffCaps = new HashMap<>();
    private final Map<UUID, List<ItemStack>> soulboundDeathSaves = new HashMap<>();
    private final Map<UUID, Long> huyenVuShieldUntil = new HashMap<>();
    private final Map<UUID, Long> tuThanhDomainUntil = new HashMap<>();
    private final Set<UUID> miningLock = new HashSet<>();

    public EnchantManager(EnchantCraftPlugin plugin) {
        this.plugin = plugin;
        this.bookEnchantKey = new NamespacedKey(plugin, "enchantcraft_book_id");
        this.bookLevelKey = new NamespacedKey(plugin, "enchantcraft_book_level");
        this.arrowEnchantsKey = new NamespacedKey(plugin, "enchantcraft_arrow_ids");
        this.guiTierKey = new NamespacedKey(plugin, "enchantcraft_gui_tier");
        this.gaiaHpModifierKey = new NamespacedKey(plugin, "giap_gaia_max_hp");
        this.villagerDoubledKey = new NamespacedKey(plugin, "het_gia_doubled");
        this.unreflectableFireballKey = new NamespacedKey(plugin, "chu_tuoc_fireball");
    }

    public void loadEnchants() {
        enchants.clear();
        randomTiers.clear();
        enchantToTierId.clear();

        File file = new File(plugin.getDataFolder(), "enchants.yml");
        if (!file.exists()) {
            plugin.saveResource("enchants.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection section = config.getConfigurationSection("enchants");
        if (section != null) {
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
                    int maxLevel = sec.getInt("max_level", 1);
                    boolean activeSkill = sec.getBoolean("active_skill", false);
                    List<String> actions = sec.getStringList("actions");

                    CustomEnchant enchant = new CustomEnchant(
                            id, displayName, target, description, trigger, chance, cooldown, maxLevel, activeSkill, actions
                    );
                    enchants.put(enchant.getId(), enchant);
                } catch (Exception e) {
                    plugin.getLogger().warning("Loi khi doc phu phep '" + id + "' trong enchants.yml: " + e.getMessage());
                }
            }
        }

        ConfigurationSection tierSection = config.getConfigurationSection("random_tiers");
        if (tierSection != null) {
            for (String tierId : tierSection.getKeys(false)) {
                ConfigurationSection tSec = tierSection.getConfigurationSection(tierId);
                if (tSec == null) continue;

                String name = tSec.getString("display_name", tierId);
                Material icon = Material.matchMaterial(tSec.getString("icon", "BOOK"));
                if (icon == null || icon.isAir()) icon = Material.BOOK;
                int slot = tSec.getInt("slot", 13);
                int costLevels = tSec.getInt("cost_levels", 25);
                int salvageLevels = tSec.getInt("salvage_levels", 5);
                List<String> salvageItems = tSec.getStringList("salvage_items");
                boolean broadcast = tSec.getBoolean("broadcast", false);
                List<String> lore = tSec.getStringList("lore");
                List<String> pool = tSec.getStringList("pool");

                String lowerTier = tierId.toLowerCase();
                randomTiers.put(lowerTier, new RandomTier(
                        lowerTier, name, icon, slot, costLevels, salvageLevels, salvageItems, broadcast, lore, pool
                ));

                for (String entry : pool) {
                    String[] parts = entry.split(":");
                    if (!parts[0].equalsIgnoreCase("TIER")) {
                        enchantToTierId.put(parts[0].toLowerCase(), lowerTier);
                    }
                }
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

    private int getAppliedLevel(PersistentDataContainer pdc, String enchantId) {
        NamespacedKey key = getAppliedKey(enchantId);
        if (pdc.has(key, PersistentDataType.INTEGER)) {
            Integer lvl = pdc.get(key, PersistentDataType.INTEGER);
            return lvl != null ? Math.max(1, lvl) : 1;
        }
        if (pdc.has(key, PersistentDataType.BYTE)) {
            return 1;
        }
        return 0;
    }

    private String formatEnchantTitle(CustomEnchant enchant, int level) {
        if (enchant.getMaxLevel() <= 1) {
            return enchant.getDisplayName();
        }
        return enchant.getDisplayName() + " " + toRoman(level);
    }

    private String toRoman(int num) {
        return switch (num) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(num);
        };
    }

    public ItemStack createEnchantBook(String enchantId) {
        return createEnchantBook(enchantId, 1);
    }

    public ItemStack createEnchantBook(String enchantId, int level) {
        CustomEnchant enchant = getEnchant(enchantId);
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        if (enchant == null) return book;

        int safeLevel = Math.min(enchant.getMaxLevel(), Math.max(1, level));
        ItemMeta meta = book.getItemMeta();
        if (meta == null) return book;

        String title = formatEnchantTitle(enchant, safeLevel);
        meta.displayName(Component.text("Sách Phù Phép: " + title, NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Áp dụng cho: " + enchant.getTargetType().getLabel(), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        if (enchant.isActiveSkill()) {
            lore.add(Component.text("Phân loại: Kỹ Năng Chủ Động (Tối đa Cấp " + toRoman(enchant.getMaxLevel()) + ")", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        } else if (enchant.getMaxLevel() > 1) {
            lore.add(Component.text("Phân loại: Bị Động Tăng Tiến (Tối đa Cấp " + toRoman(enchant.getMaxLevel()) + ")", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Phân loại: Đặc Thù (Cố định)", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.text("Hiệu ứng: " + enchant.getDescription(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("▶ Chuột Phải vào trang bị (tối đa 6 sách/món)", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        if (enchant.getMaxLevel() > 1 && safeLevel < enchant.getMaxLevel()) {
            lore.add(Component.text("▶ Ghép 2 sách/đồ cùng cấp " + toRoman(safeLevel) + " để nâng lên cấp " + toRoman(safeLevel + 1), NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("▶ Gõ /phanra khi cầm sách để tái chế lấy XP & khoáng sản", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(bookEnchantKey, PersistentDataType.STRING, enchant.getId());
        meta.getPersistentDataContainer().set(bookLevelKey, PersistentDataType.INTEGER, safeLevel);

        book.setItemMeta(meta);
        return book;
    }

    // =========================================================================
    // CƠ CHẾ PHÂN RÃ SÁCH (/phanra)
    // =========================================================================

    public void salvageHeldBook(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.ENCHANTED_BOOK || !hand.hasItemMeta()) {
            player.sendMessage(Component.text("Bạn phải cầm Sách Phù Phép (EnchantCraft) trên tay chính để phân rã!", NamedTextColor.RED));
            playSoundSafe(player, "entity.villager.no", 1.0f);
            return;
        }

        PersistentDataContainer pdc = hand.getItemMeta().getPersistentDataContainer();
        String enchantId = pdc.get(bookEnchantKey, PersistentDataType.STRING);
        if (enchantId == null) {
            player.sendMessage(Component.text("Cuốn sách này không phải sách phù phép của EnchantCraft!", NamedTextColor.RED));
            playSoundSafe(player, "entity.villager.no", 1.0f);
            return;
        }

        int bookLevel = pdc.getOrDefault(bookLevelKey, PersistentDataType.INTEGER, 1);
        CustomEnchant enchant = getEnchant(enchantId);
        String tierId = enchantToTierId.getOrDefault(enchantId.toLowerCase(), "thuong");
        RandomTier tier = randomTiers.get(tierId);

        int xpReward = ((tier != null) ? tier.salvageLevels : 5) * bookLevel;
        List<String> itemRewards = (tier != null) ? tier.salvageItems : List.of("EMERALD:2");

        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        if (xpReward > 0) {
            player.setLevel(player.getLevel() + xpReward);
        }

        List<String> rewardSummary = new ArrayList<>();
        if (xpReward > 0) rewardSummary.add("+" + xpReward + " Cấp XP");

        for (String raw : itemRewards) {
            String[] parts = raw.split(":");
            Material mat = Material.matchMaterial(parts[0].toUpperCase());
            int amount = (parts.length > 1 ? parseIntSafe(parts[1], 1) : 1) * bookLevel;
            if (mat != null && !mat.isAir() && amount > 0) {
                ItemStack drop = new ItemStack(mat, amount);
                Map<Integer, ItemStack> rem = player.getInventory().addItem(drop);
                rem.values().forEach(r -> player.getWorld().dropItemNaturally(player.getLocation(), r));
                rewardSummary.add(amount + "x " + mat.name());
            }
        }

        String bookName = (enchant != null) ? formatEnchantTitle(enchant, bookLevel) : enchantId;
        playSoundSafe(player, "block.grindstone.use", 1.1f);
        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1.2, 0), 25, 0.4, 0.4, 0.4, 0.2);
        player.sendMessage(Component.text("♻ Phân rã thành công ", NamedTextColor.GREEN)
                .append(Component.text(bookName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(" → Nhận: ", NamedTextColor.GREEN))
                .append(Component.text(String.join(", ", rewardSummary), NamedTextColor.GOLD)));
    }

    // =========================================================================
    // GIAO DIỆN QUAY SÁCH /random ce
    // =========================================================================

    public void openRandomGui(Player player) {
        Inventory gui = Bukkit.createInventory(
                new RandomGuiHolder(),
                27,
                Component.text("Kho Báu Phù Phép (Dùng Cấp XP)", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
        );

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(Component.empty());
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < 27; i++) {
            gui.setItem(i, filler);
        }

        for (RandomTier tier : randomTiers.values()) {
            if (tier.slot < 0 || tier.slot >= 27) continue;

            ItemStack item = new ItemStack(tier.icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tier.displayName)
                        .decoration(TextDecoration.ITALIC, false));

                List<Component> lore = new ArrayList<>();
                for (String line : tier.lore) {
                    lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line)
                            .decoration(TextDecoration.ITALIC, false));
                }
                lore.add(Component.empty());
                lore.add(Component.text("Chi phí mở: ", NamedTextColor.GRAY)
                        .append(Component.text(tier.costLevels + " Cấp XP", NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Cấp hiện tại của bạn: ", NamedTextColor.GRAY)
                        .append(Component.text(player.getLevel() + " Cấp XP", player.getLevel() >= tier.costLevels ? NamedTextColor.AQUA : NamedTextColor.RED))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(Component.text("▶ Bấm vào đây để mở ngay!", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));

                meta.lore(lore);
                meta.setEnchantmentGlintOverride(true);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
                meta.getPersistentDataContainer().set(guiTierKey, PersistentDataType.STRING, tier.id);
                item.setItemMeta(meta);
            }
            gui.setItem(tier.slot, item);
        }

        ItemStack salvageBtn = new ItemStack(Material.GRINDSTONE);
        ItemMeta sMeta = salvageBtn.getItemMeta();
        if (sMeta != null) {
            sMeta.displayName(Component.text("♻ Lò Phân Rã Sách", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            sMeta.lore(List.of(
                    Component.text("Cầm Sách Phù Phép trên tay chính rồi bấm", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("vào đây (hoặc gõ /phanra) để tái chế sách", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("thành Cấp XP và Khoáng Sản hiếm!", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("▶ Bấm để phân rã sách đang cầm!", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            ));
            sMeta.getPersistentDataContainer().set(guiTierKey, PersistentDataType.STRING, "SALVAGE_BUTTON");
            salvageBtn.setItemMeta(sMeta);
        }
        gui.setItem(22, salvageBtn);

        player.openInventory(gui);
    }

    @EventHandler
    public void onRandomGuiClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RandomGuiHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String tierId = clicked.getItemMeta().getPersistentDataContainer().get(guiTierKey, PersistentDataType.STRING);
        if (tierId == null) return;

        if (tierId.equals("SALVAGE_BUTTON")) {
            player.closeInventory();
            salvageHeldBook(player);
            return;
        }

        RandomTier tier = randomTiers.get(tierId);
        if (tier == null) return;

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Component.text("Túi đồ của bạn đã đầy, hãy dọn trống ít nhất 1 ô trước khi mở!", NamedTextColor.RED));
            playSoundSafe(player, "entity.villager.no", 1.0f);
            return;
        }

        if (player.getLevel() < tier.costLevels) {
            player.sendMessage(Component.text("Bạn cần " + tier.costLevels + " Cấp XP để mở gói này (hiện có: " + player.getLevel() + " cấp)!", NamedTextColor.RED));
            playSoundSafe(player, "entity.villager.no", 1.0f);
            return;
        }

        String chosenEnchantId = pickFromPool(tier.pool, 0);
        CustomEnchant chosenEnchant = chosenEnchantId != null ? getEnchant(chosenEnchantId) : null;
        if (chosenEnchant == null) {
            player.sendMessage(Component.text("Gói quay này chưa có phù phép hợp lệ trong enchants.yml!", NamedTextColor.RED));
            return;
        }

        player.setLevel(player.getLevel() - tier.costLevels);
        player.getInventory().addItem(createEnchantBook(chosenEnchant.getId(), 1));
        player.closeInventory();

        Component tierNameComp = LegacyComponentSerializer.legacyAmpersand().deserialize(tier.displayName);
        player.sendMessage(Component.text("Bạn đã mở ", NamedTextColor.GREEN)
                .append(tierNameComp)
                .append(Component.text(" và nhận được sách ", NamedTextColor.GREEN))
                .append(Component.text(formatEnchantTitle(chosenEnchant, 1), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GREEN)));

        if (tier.broadcast) {
            playSoundSafe(player, "ui.toast.challenge_complete", 1.0f);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 25, 0.5, 0.5, 0.5, 0.2);
            Bukkit.broadcast(Component.text("★ ", NamedTextColor.GOLD)
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" vừa mở ", NamedTextColor.GOLD))
                    .append(tierNameComp)
                    .append(Component.text(" và nhận được ", NamedTextColor.GOLD))
                    .append(Component.text(formatEnchantTitle(chosenEnchant, 1), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                    .append(Component.text("!", NamedTextColor.GOLD)));
        } else {
            playSoundSafe(player, "entity.player.levelup", 1.2f);
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
        }
    }

    @EventHandler
    public void onRandomGuiDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RandomGuiHolder) {
            event.setCancelled(true);
        }
    }

    private String pickFromPool(List<String> pool, int depth) {
        if (pool == null || pool.isEmpty() || depth > 5) return null;
        List<String> entries = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int totalWeight = 0;

        for (String raw : pool) {
            String[] parts = raw.split(":");
            if (parts[0].equalsIgnoreCase("TIER") && parts.length >= 2) {
                int w = parts.length >= 3 ? parseIntSafe(parts[2], 10) : 10;
                entries.add("TIER:" + parts[1].toLowerCase());
                weights.add(w);
                totalWeight += w;
            } else {
                int w = parts.length >= 2 ? parseIntSafe(parts[1], 10) : 10;
                entries.add(parts[0].toLowerCase());
                weights.add(w);
                totalWeight += w;
            }
        }

        if (totalWeight <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < entries.size(); i++) {
            cumulative += weights.get(i);
            if (roll < cumulative) {
                String chosen = entries.get(i);
                if (chosen.startsWith("TIER:")) {
                    RandomTier subTier = randomTiers.get(chosen.substring(5));
                    return subTier != null ? pickFromPool(subTier.pool, depth + 1) : null;
                }
                return chosen;
            }
        }
        return null;
    }

    // =========================================================================
    // ÉP SÁCH (TỐI ĐA 6 SÁCH, CHẶN TRÙNG KỸ NĂNG CHỦ ĐỘNG, CỘNG DỒN CẤP ĐỘ)
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof RandomGuiHolder) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
            ItemStack current = event.getCurrentItem();
            ItemStack cursor = event.getCursor();
            if (event.getClickedInventory() == player.getInventory() && event.isShiftClick() && hasAction(current, "SOULBOUND")) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Vật phẩm đã Liên Kết linh hồn, không thể cất vào rương!", NamedTextColor.RED));
                return;
            }
            if (event.getClickedInventory() != player.getInventory() && hasAction(cursor, "SOULBOUND")) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Vật phẩm đã Liên Kết linh hồn, không thể cất vào rương!", NamedTextColor.RED));
                return;
            }
            if (event.getClick() == ClickType.NUMBER_KEY && event.getClickedInventory() != player.getInventory()) {
                ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
                if (hasAction(hotbar, "SOULBOUND")) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (!event.isRightClick() || event.getClickedInventory() == null) return;
        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();

        if (cursor.getType() != Material.ENCHANTED_BOOK || target == null || target.getType().isAir()) {
            return;
        }

        ItemMeta bookMeta = cursor.getItemMeta();
        if (bookMeta == null) return;

        PersistentDataContainer bookPdc = bookMeta.getPersistentDataContainer();
        String enchantId = bookPdc.get(bookEnchantKey, PersistentDataType.STRING);
        if (enchantId == null) return;

        CustomEnchant enchant = getEnchant(enchantId);
        if (enchant == null) return;

        int bookLevel = bookPdc.getOrDefault(bookLevelKey, PersistentDataType.INTEGER, 1);

        // Trường hợp 1: Ghép 2 cuốn sách giống nhau cùng cấp độ trong túi đồ để nâng cấp
        if (target.getType() == Material.ENCHANTED_BOOK && target.hasItemMeta()) {
            PersistentDataContainer targetBookPdc = target.getItemMeta().getPersistentDataContainer();
            String targetEnchantId = targetBookPdc.get(bookEnchantKey, PersistentDataType.STRING);
            if (enchant.getId().equalsIgnoreCase(targetEnchantId)) {
                int targetBookLevel = targetBookPdc.getOrDefault(bookLevelKey, PersistentDataType.INTEGER, 1);
                if (enchant.getMaxLevel() <= 1) {
                    player.sendMessage(Component.text("Sách " + enchant.getDisplayName() + " là sách đặc thù cố định, không có cấp độ!", NamedTextColor.RED));
                    return;
                }
                if (bookLevel != targetBookLevel) {
                    player.sendMessage(Component.text("Hai cuốn sách phải cùng cấp độ (" + toRoman(bookLevel) + " và " + toRoman(targetBookLevel) + ") mới có thể hợp nhất!", NamedTextColor.RED));
                    return;
                }
                if (targetBookLevel >= enchant.getMaxLevel()) {
                    player.sendMessage(Component.text("Sách " + enchant.getDisplayName() + " đã đạt cấp tối đa (" + toRoman(enchant.getMaxLevel()) + ")!", NamedTextColor.RED));
                    return;
                }

                event.setCancelled(true);
                int nextLevel = targetBookLevel + 1;

                if (cursor.getAmount() > 1) {
                    cursor.setAmount(cursor.getAmount() - 1);
                } else {
                    player.setItemOnCursor(null);
                }

                if (target.getAmount() > 1) {
                    target.setAmount(target.getAmount() - 1);
                    ItemStack upgradedBook = createEnchantBook(enchant.getId(), nextLevel);
                    Map<Integer, ItemStack> rem = player.getInventory().addItem(upgradedBook);
                    rem.values().forEach(r -> player.getWorld().dropItemNaturally(player.getLocation(), r));
                } else {
                    event.setCurrentItem(createEnchantBook(enchant.getId(), nextLevel));
                }

                playSoundSafe(player, "block.anvil.use", 1.3f);
                player.sendMessage(Component.text("✦ Hợp nhất thành công lên ", NamedTextColor.GREEN)
                        .append(Component.text(formatEnchantTitle(enchant, nextLevel), NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text("!", NamedTextColor.GREEN)));
                return;
            }
        }

        // Trường hợp 2: Ép sách vào trang bị
        if (!enchant.getTargetType().matches(target.getType())) {
            player.sendMessage(Component.text("Phù phép " + enchant.getDisplayName() + " chỉ ép được vào: " + enchant.getTargetType().getLabel(), NamedTextColor.RED));
            return;
        }

        ItemMeta targetMeta = target.getItemMeta();
        if (targetMeta == null) return;

        PersistentDataContainer targetPdc = targetMeta.getPersistentDataContainer();
        int existingLevel = getAppliedLevel(targetPdc, enchant.getId());

        if (existingLevel > 0) {
            if (enchant.getMaxLevel() <= 1) {
                player.sendMessage(Component.text("Trang bị này đã có phù phép " + enchant.getDisplayName() + " rồi (không có cấp cao hơn)!", NamedTextColor.RED));
                return;
            }
            if (existingLevel >= enchant.getMaxLevel()) {
                player.sendMessage(Component.text("Phù phép " + enchant.getDisplayName() + " trên trang bị đã đạt cấp tối đa (" + toRoman(enchant.getMaxLevel()) + ")!", NamedTextColor.RED));
                return;
            }
            if (bookLevel != existingLevel) {
                player.sendMessage(Component.text("Cần dùng sách " + formatEnchantTitle(enchant, existingLevel) + " cùng cấp để nâng cấp trang bị!", NamedTextColor.RED));
                return;
            }

            int nextLevel = existingLevel + 1;
            targetPdc.set(getAppliedKey(enchant.getId()), PersistentDataType.INTEGER, nextLevel);
            updateLoreLine(targetMeta, enchant.getDisplayName(), formatEnchantTitle(enchant, nextLevel));
            target.setItemMeta(targetMeta);

            event.setCancelled(true);
            if (cursor.getAmount() > 1) {
                cursor.setAmount(cursor.getAmount() - 1);
            } else {
                player.setItemOnCursor(null);
            }

            playSoundSafe(player, "block.anvil.use", 1.3f);
            player.sendMessage(Component.text("✦ Đã nâng cấp phù phép trên trang bị lên ", NamedTextColor.GREEN)
                    .append(Component.text(formatEnchantTitle(enchant, nextLevel), NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text("!", NamedTextColor.GREEN)));
            return;
        }

        int appliedCount = 0;
        CustomEnchant existingActiveSkill = null;
        for (CustomEnchant e : enchants.values()) {
            if (getAppliedLevel(targetPdc, e.getId()) > 0) {
                appliedCount++;
                if (e.isActiveSkill()) {
                    existingActiveSkill = e;
                }
            }
        }

        if (appliedCount >= MAX_ENCHANTS_PER_ITEM) {
            player.sendMessage(Component.text("Trang bị này đã đạt giới hạn tối đa " + MAX_ENCHANTS_PER_ITEM + " phù phép!", NamedTextColor.RED));
            playSoundSafe(player, "entity.villager.no", 1.0f);
            return;
        }

        if (enchant.isActiveSkill() && existingActiveSkill != null) {
            player.sendMessage(Component.text("Trang bị này đã có kỹ năng chủ động (" + existingActiveSkill.getDisplayName() + "), không thể ép chung thêm sách kỹ năng!", NamedTextColor.RED));
            playSoundSafe(player, "entity.villager.no", 1.0f);
            return;
        }

        targetPdc.set(getAppliedKey(enchant.getId()), PersistentDataType.INTEGER, bookLevel);

        List<Component> lore = targetMeta.hasLore() && targetMeta.lore() != null
                ? new ArrayList<>(targetMeta.lore())
                : new ArrayList<>();
        NamedTextColor lineColor = enchant.isActiveSkill() ? NamedTextColor.GOLD : NamedTextColor.RED;
        lore.add(Component.text(formatEnchantTitle(enchant, bookLevel), lineColor)
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

        playSoundSafe(player, "block.enchantment_table.use", 1.2f);
        player.sendMessage(Component.text("Ép thành công phù phép " + formatEnchantTitle(enchant, bookLevel) + " (" + (appliedCount + 1) + "/" + MAX_ENCHANTS_PER_ITEM + ")!", NamedTextColor.GREEN));
    }

    private void updateLoreLine(ItemMeta meta, String baseDisplayName, String newTitle) {
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < lore.size(); i++) {
            String plain = PlainTextComponentSerializer.plainText().serialize(lore.get(i));
            if (plain.startsWith(baseDisplayName)) {
                lore.set(i, Component.text(newTitle, lore.get(i).color() != null ? lore.get(i).color() : NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lore.add(Component.text(newTitle, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (hasAction(event.getItemDrop().getItemStack(), "SOULBOUND")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Vật phẩm đã Liên Kết linh hồn, không thể vứt bỏ cho đến khi hỏng!", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<ItemStack> saved = new ArrayList<>();
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (hasAction(drop, "SOULBOUND")) {
                saved.add(drop.clone());
                it.remove();
            }
        }
        if (!saved.isEmpty()) {
            soulboundDeathSaves.put(player.getUniqueId(), saved);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        List<ItemStack> saved = soulboundDeathSaves.remove(event.getPlayer().getUniqueId());
        if (saved != null) {
            saved.forEach(item -> event.getPlayer().getInventory().addItem(item));
        }
    }

    // =========================================================================
    // GIAO DỊCH DÂN LÀNG (HÉT GIÁ)
    // =========================================================================

    @EventHandler
    public void onMerchantOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory() instanceof MerchantInventory merchantInv)) return;

        boolean hasPriceCurse = false;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (hasAction(armor, "DOUBLE_TRADE")) {
                hasPriceCurse = true;
                break;
            }
        }
        if (!hasPriceCurse) return;

        Merchant merchant = merchantInv.getMerchant();
        if (merchant instanceof Entity entity) {
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            if (pdc.has(villagerDoubledKey, PersistentDataType.BYTE)) return;
            pdc.set(villagerDoubledKey, PersistentDataType.BYTE, (byte) 1);
        }

        List<MerchantRecipe> updated = new ArrayList<>();
        for (MerchantRecipe old : merchant.getRecipes()) {
            MerchantRecipe copy = new MerchantRecipe(
                    old.getResult(), old.getUses(), old.getMaxUses(),
                    old.hasExperienceReward(), old.getVillagerExperience(),
                    old.getPriceMultiplier(), old.getDemand(), old.getSpecialPrice()
            );
            List<ItemStack> doubledIngredients = new ArrayList<>();
            for (ItemStack ing : old.getIngredients()) {
                if (ing != null && !ing.getType().isAir()) {
                    ItemStack cloned = ing.clone();
                    cloned.setAmount(Math.min(cloned.getMaxStackSize(), cloned.getAmount() * 2));
                    doubledIngredients.add(cloned);
                }
            }
            copy.setIngredients(doubledIngredients);
            updated.add(copy);
        }
        merchant.setRecipes(updated);
        player.sendMessage(Component.text("Lời nguyền Hét Giá: Dân làng thấy bộ đồ của bạn liền tăng giá gấp đôi!", NamedTextColor.RED));
    }

    // =========================================================================
    // BẮN CUNG, PHÓNG ĐINH BA & CHẶN PHẢN CẦU LỬA CHU TƯỚC
    // =========================================================================

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) return;
        ItemStack bow = event.getBow();
        if (bow == null || !bow.hasItemMeta()) return;

        PersistentDataContainer pdc = bow.getItemMeta().getPersistentDataContainer();
        List<String> entries = new ArrayList<>();
        for (CustomEnchant enchant : enchants.values()) {
            int lvl = getAppliedLevel(pdc, enchant.getId());
            if (lvl > 0) {
                entries.add(enchant.getId() + ":" + lvl);
                double scaledChance = Math.min(1.0, enchant.getChance() * (1.0 + (lvl - 1) * 0.15));
                if (enchant.getTriggerType() == TriggerType.SHOOT
                        && ThreadLocalRandom.current().nextDouble() <= scaledChance
                        && !isOnCooldown(shooter, enchant)) {
                    executeActions(enchant.getActions(), shooter, null, shooter.getLocation(), 0, bow, null, lvl);
                }
            }
        }
        if (!entries.isEmpty()) {
            event.getProjectile().getPersistentDataContainer().set(
                    arrowEnchantsKey, PersistentDataType.STRING, String.join(",", entries)
            );
        }
    }

    @EventHandler
    public void onTridentLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        if (!(trident.getShooter() instanceof Player shooter)) return;

        ItemStack hand = shooter.getInventory().getItemInMainHand();
        if (hand.getType() != Material.TRIDENT) {
            hand = shooter.getInventory().getItemInOffHand();
        }
        if (hand.getType() == Material.TRIDENT && hand.hasItemMeta()) {
            triggerItemEnchants(hand, TriggerType.SHOOT, shooter, null, shooter.getLocation(), 0, null);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof LargeFireball fireball
                && fireball.getPersistentDataContainer().has(unreflectableFireballKey, PersistentDataType.BYTE)) {
            int lvl = fireball.getPersistentDataContainer().getOrDefault(bookLevelKey, PersistentDataType.INTEGER, 1);
            Location hitLoc = fireball.getLocation();
            Player shooter = fireball.getShooter() instanceof Player p ? p : null;
            fireball.remove();

            double radius = 4.0 + lvl * 0.8;
            double dmg = 8.0 + lvl * 2.5;

            hitLoc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, hitLoc, 1, 0, 0, 0, 0);
            hitLoc.getWorld().spawnParticle(Particle.FLAME, hitLoc, 45, 2.2, 1.5, 2.2, 0.08);
            if (shooter != null) playSoundSafe(shooter, "entity.generic.explode", 0.85f);

            for (Entity nearby : hitLoc.getWorld().getNearbyEntities(hitLoc, radius, radius, radius)) {
                if (nearby instanceof LivingEntity le && le != shooter) {
                    le.setFireTicks(80 + lvl * 20);
                    le.damage(dmg, shooter);
                    Vector push = le.getLocation().toVector().subtract(hitLoc.toVector());
                    if (push.lengthSquared() > 0.01) {
                        le.setVelocity(push.normalize().multiply(1.4).setY(0.55));
                    }
                }
            }
            return;
        }

        if (event.getHitEntity() != null) return;
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;

        String rawIds = arrow.getPersistentDataContainer().get(arrowEnchantsKey, PersistentDataType.STRING);
        if (rawIds == null || rawIds.isEmpty()) return;

        for (String token : rawIds.split(",")) {
            String[] p = token.split(":");
            CustomEnchant enchant = getEnchant(p[0]);
            int lvl = p.length > 1 ? parseIntSafe(p[1], 1) : 1;
            if (enchant == null || enchant.getTriggerType() != TriggerType.ATTACK) continue;
            double scaledChance = Math.min(1.0, enchant.getChance() * (1.0 + (lvl - 1) * 0.15));
            if (ThreadLocalRandom.current().nextDouble() > scaledChance) continue;
            if (isOnCooldown(shooter, enchant)) continue;
            executeActions(enchant.getActions(), shooter, null, arrow.getLocation(), 0, null, null, lvl);
        }
    }

    // =========================================================================
    // CHIẾN ĐẤU, PHÒNG THỦ, KẾT GIỚI HUYỀN VŨ & LÃNH ĐỊA TỨ THÁNH
    // =========================================================================

    private boolean isHuyenVuShieldActive(Player player) {
        return System.currentTimeMillis() < huyenVuShieldUntil.getOrDefault(player.getUniqueId(), 0L);
    }

    private boolean isTuThanhDomainActive(Player player) {
        return System.currentTimeMillis() < tuThanhDomainUntil.getOrDefault(player.getUniqueId(), 0L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGeneralDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // Kết Giới Quy Giáp (Huyền Vũ) chặn đứng mọi sát thương
        if (isHuyenVuShieldActive(victim)) {
            event.setCancelled(true);
            victim.getWorld().spawnParticle(Particle.END_ROD, victim.getLocation().add(0, 1.1, 0), 10, 0.5, 0.6, 0.5, 0.04);
            playSoundSafe(victim, "item.shield.block", 1.2f);
            return;
        }

        // Lãnh Địa Tứ Thánh giảm 50% mọi sát thương nhận vào
        if (isTuThanhDomainActive(victim)) {
            event.setDamage(event.getDamage() * 0.5);
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (hasAction(armor, "NO_FALL_DAMAGE")) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (victim.getHealth() - event.getFinalDamage() <= 0) {
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor == null || armor.getType().isAir() || !armor.hasItemMeta()) continue;
                PersistentDataContainer pdc = armor.getItemMeta().getPersistentDataContainer();
                for (CustomEnchant enchant : enchants.values()) {
                    if (enchant.getTriggerType() != TriggerType.FATAL_DAMAGE) continue;
                    int lvl = getAppliedLevel(pdc, enchant.getId());
                    if (lvl <= 0) continue;
                    if (isOnCooldown(victim, enchant)) continue;

                    event.setCancelled(true);
                    executeActions(enchant.getActions(), victim, null, victim.getLocation(), 0, armor, event, lvl);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof LargeFireball fireball
                && fireball.getPersistentDataContainer().has(unreflectableFireballKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }

        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity target) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (!weapon.getType().isAir() && weapon.hasItemMeta()) {
                triggerItemEnchants(weapon, TriggerType.ATTACK, attacker, target, target.getLocation(), event.getFinalDamage(), event);
            }
        }

        if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter && event.getEntity() instanceof LivingEntity target) {
            String rawIds = arrow.getPersistentDataContainer().get(arrowEnchantsKey, PersistentDataType.STRING);
            if (rawIds != null && !rawIds.isEmpty()) {
                for (String token : rawIds.split(",")) {
                    String[] p = token.split(":");
                    CustomEnchant enchant = getEnchant(p[0]);
                    int lvl = p.length > 1 ? parseIntSafe(p[1], 1) : 1;
                    if (enchant == null || enchant.getTriggerType() != TriggerType.ATTACK) continue;
                    double scaledChance = Math.min(1.0, enchant.getChance() * (1.0 + (lvl - 1) * 0.15));
                    if (ThreadLocalRandom.current().nextDouble() > scaledChance) continue;
                    if (isOnCooldown(shooter, enchant)) continue;
                    executeActions(enchant.getActions(), shooter, target, target.getLocation(), event.getFinalDamage(), null, event, lvl);
                }
            }
        }

        if (event.getEntity() instanceof Player victim) {
            LivingEntity attacker = null;
            if (event.getDamager() instanceof LivingEntity le) {
                attacker = le;
            } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le) {
                attacker = le;
            }

            // Bật ngược đạn bay vào Kết Giới Huyền Vũ
            if (isHuyenVuShieldActive(victim)) {
                event.setCancelled(true);
                if (event.getDamager() instanceof Projectile proj) {
                    proj.setVelocity(proj.getVelocity().multiply(-1.3));
                    proj.setShooter(victim);
                } else if (attacker != null) {
                    Vector push = attacker.getLocation().toVector().subtract(victim.getLocation().toVector());
                    if (push.lengthSquared() > 0.01) {
                        attacker.setVelocity(push.normalize().multiply(1.3).setY(0.45));
                    }
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3));
                }
                playSoundSafe(victim, "item.shield.block", 1.2f);
                return;
            }

            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                    triggerItemEnchants(armor, TriggerType.DEFEND, victim, attacker, victim.getLocation(), event.getFinalDamage(), event);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) return;

        List<ItemStack> activeItems = getActiveEquipment(killer);
        List<LeveledAction> actions = collectLeveledActions(activeItems, TriggerType.KILL, killer);
        if (actions.isEmpty()) return;

        for (LeveledAction la : actions) {
            String[] parts = la.action.split(":");
            int lvl = la.level;
            switch (parts[0].toUpperCase()) {
                case "MIDAS_TOUCH" -> {
                    double roll = ThreadLocalRandom.current().nextDouble();
                    if (roll < 0.01 * lvl) {
                        event.getDrops().add(new ItemStack(Material.GOLDEN_APPLE));
                    } else if (roll < 0.12 * lvl) {
                        event.getDrops().add(new ItemStack(Material.GOLD_INGOT));
                    }
                }
                case "DOUBLE_DROP" -> {
                    double chance = (parts.length > 1 ? Double.parseDouble(parts[1]) : 0.20) * (1.0 + (lvl - 1) * 0.25);
                    if (ThreadLocalRandom.current().nextDouble() < chance) {
                        List<ItemStack> extra = new ArrayList<>();
                        for (ItemStack d : event.getDrops()) extra.add(d.clone());
                        event.getDrops().addAll(extra);
                    }
                }
                case "VAPORIZE_DROP" -> {
                    double chance = parts.length > 1 ? Double.parseDouble(parts[1]) : 0.20;
                    if (ThreadLocalRandom.current().nextDouble() < chance) {
                        event.getDrops().clear();
                    }
                }
                case "ROT_DROP" -> {
                    double chance = parts.length > 1 ? Double.parseDouble(parts[1]) : 0.25;
                    if (ThreadLocalRandom.current().nextDouble() < chance && !event.getDrops().isEmpty()) {
                        int count = event.getDrops().size();
                        event.getDrops().clear();
                        event.getDrops().add(new ItemStack(Material.ROTTEN_FLESH, count));
                    }
                }
                case "EXP_BOOST" -> event.setDroppedExp(event.getDroppedExp() + (parts.length > 1 ? Integer.parseInt(parts[1]) : 5) * lvl);
            }
        }
    }

    // =========================================================================
    // ĐÀO MỎ, NÔNG NGHIỆP & ĐỘN THỔ
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (miningLock.contains(player.getUniqueId())) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType().isAir() || !tool.hasItemMeta()) return;

        List<LeveledAction> activeActions = collectLeveledActions(List.of(tool), TriggerType.MINE, player);
        if (activeActions.isEmpty()) return;

        boolean mine3x3 = false;
        int veinLimit = 0;
        boolean harvest3x3 = false;
        boolean autoSmelt = false;
        boolean magnet = false;
        int expBoost = 0;
        double blockifyChance = 0;
        boolean gambleOre = false;
        double midasMult = 0;
        double doubleDropChance = 0;
        double vaporizeChance = 0;
        double rotChance = 0;
        List<LeveledAction> normalActions = new ArrayList<>();

        for (LeveledAction la : activeActions) {
            String[] parts = la.action.split(":");
            int lvl = la.level;
            switch (parts[0].toUpperCase()) {
                case "MINE_3X3" -> mine3x3 = true;
                case "VEIN_MINE" -> veinLimit = (parts.length > 1 ? Integer.parseInt(parts[1]) : 16) + (lvl - 1) * 8;
                case "HARVEST_3X3" -> harvest3x3 = true;
                case "AUTO_SMELT" -> autoSmelt = true;
                case "MAGNET" -> magnet = true;
                case "EXP_BOOST" -> expBoost += (parts.length > 1 ? Integer.parseInt(parts[1]) : 2) * lvl;
                case "BLOCKIFY" -> blockifyChance = Math.max(blockifyChance, (parts.length > 1 ? Double.parseDouble(parts[1]) : 0.15) * (1.0 + (lvl - 1) * 0.25));
                case "GAMBLE_ORE" -> gambleOre = true;
                case "MIDAS_TOUCH" -> midasMult = Math.max(midasMult, 1.0 + (lvl - 1) * 0.30);
                case "DOUBLE_DROP" -> doubleDropChance = Math.max(doubleDropChance, (parts.length > 1 ? Double.parseDouble(parts[1]) : 0.20) * (1.0 + (lvl - 1) * 0.25));
                case "VAPORIZE_DROP" -> vaporizeChance = Math.max(vaporizeChance, parts.length > 1 ? Double.parseDouble(parts[1]) : 0.20);
                case "ROT_DROP" -> rotChance = Math.max(rotChance, parts.length > 1 ? Double.parseDouble(parts[1]) : 0.25);
                default -> normalActions.add(la);
            }
        }

        Block origin = event.getBlock();

        if (harvest3x3 && origin.getBlockData() instanceof Ageable) {
            event.setCancelled(true);
            miningLock.add(player.getUniqueId());
            try {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        Block b = origin.getRelative(x, 0, z);
                        if (b.getBlockData() instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge()) {
                            Collection<ItemStack> drops = b.getDrops(tool, player);
                            drops.forEach(d -> b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), d));
                            ageable.setAge(0);
                            b.setBlockData(ageable);
                            damageTool(tool);
                        }
                    }
                }
            } finally {
                miningLock.remove(player.getUniqueId());
            }
            return;
        }

        Set<Block> targetBlocks = new LinkedHashSet<>();
        targetBlocks.add(origin);

        if (mine3x3) {
            targetBlocks.addAll(get3x3Blocks(origin, lastClickedFace.getOrDefault(player.getUniqueId(), BlockFace.NORTH)));
        }
        if (veinLimit > 0 && isVeinBlock(origin.getType())) {
            targetBlocks.addAll(getVeinBlocks(origin, veinLimit));
        }

        boolean customDropHandling = autoSmelt || magnet || blockifyChance > 0 || gambleOre || midasMult > 0
                || doubleDropChance > 0 || vaporizeChance > 0 || rotChance > 0 || targetBlocks.size() > 1;

        if (customDropHandling) {
            event.setDropItems(false);
            miningLock.add(player.getUniqueId());
            try {
                for (Block b : targetBlocks) {
                    Material bType = b.getType();
                    if (bType.isAir() || b.isLiquid() || bType.getHardness() < 0) continue;

                    List<ItemStack> drops = new ArrayList<>(b.getDrops(tool, player));

                    if (b != origin) {
                        b.getWorld().playEffect(b.getLocation(), Effect.STEP_SOUND, b.getBlockData());
                        b.setType(Material.AIR);
                        damageTool(tool);
                    }

                    if (gambleOre && isOreBlock(bType)) {
                        double r = ThreadLocalRandom.current().nextDouble();
                        drops.clear();
                        if (r < 0.06) {
                            drops.add(new ItemStack(getOreBlockForm(bType), 1));
                            player.sendActionBar(Component.text("★ HÊN XUI: Nổ hũ nguyên Khối!", NamedTextColor.GOLD));
                        } else if (r < 0.26) {
                            drops.add(new ItemStack(getOreIngotForm(bType), 1));
                        } else if (r < 0.70) {
                            drops.addAll(b.getDrops(tool, player));
                        } else {
                            player.sendActionBar(Component.text("✖ HÊN XUI: Quặng hóa thành không khí!", NamedTextColor.RED));
                        }
                    }

                    if (blockifyChance > 0 && isOreBlock(bType) && ThreadLocalRandom.current().nextDouble() < blockifyChance) {
                        drops.clear();
                        drops.add(new ItemStack(getOreBlockForm(bType), 1));
                    }

                    if (vaporizeChance > 0 && ThreadLocalRandom.current().nextDouble() < vaporizeChance) {
                        drops.clear();
                    }

                    if (rotChance > 0 && !drops.isEmpty() && ThreadLocalRandom.current().nextDouble() < rotChance) {
                        drops.clear();
                        drops.add(new ItemStack(Material.DIRT, 1));
                    }

                    if (midasMult > 0 && !drops.isEmpty()) {
                        double mr = ThreadLocalRandom.current().nextDouble();
                        if (mr < 0.01 * midasMult) {
                            drops.clear();
                            drops.add(new ItemStack(Material.GOLDEN_APPLE, 1));
                        } else if (mr < 0.12 * midasMult) {
                            drops.clear();
                            drops.add(new ItemStack(Material.GOLD_INGOT, 1));
                        }
                    }

                    if (doubleDropChance > 0 && !drops.isEmpty() && ThreadLocalRandom.current().nextDouble() < doubleDropChance) {
                        List<ItemStack> doubled = new ArrayList<>();
                        for (ItemStack d : drops) doubled.add(d.clone());
                        drops.addAll(doubled);
                    }

                    for (ItemStack drop : drops) {
                        ItemStack finalDrop = autoSmelt ? smeltItem(drop) : drop;
                        if (magnet) {
                            Map<Integer, ItemStack> leftover = player.getInventory().addItem(finalDrop);
                            leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
                        } else {
                            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), finalDrop);
                        }
                    }
                }
            } finally {
                miningLock.remove(player.getUniqueId());
            }
        }

        if (expBoost > 0) {
            event.setExpToDrop(event.getExpToDrop() + expBoost);
        }

        for (LeveledAction la : normalActions) {
            executeActions(List.of(la.action), player, null, origin.getLocation(), 0, tool, null, la.level);
        }
    }

    // =========================================================================
    // TƯƠNG TÁC (CHUỘT TRÁI / CHUỘT PHẢI) & BẤM SHIFT
    // =========================================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getBlockFace() != null) {
            lastClickedFace.put(player.getUniqueId(), event.getBlockFace());
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();

        if (event.getAction().isLeftClick() && item.getType() == Material.BOW) {
            int chuTuocLvl = getAppliedLevel(pdc, "chu_tuoc");
            if (chuTuocLvl > 0 && !isOnCooldownKey(player, "chu_tuoc_left_click", 5)) {
                executeActions(List.of("CHU_TUOC_FIREBALLS:5"), player, null, player.getLocation(), 0, item, null, chuTuocLvl);
            }
        }

        if (!event.getAction().isRightClick()) return;

        int thanhLongLvl = getAppliedLevel(pdc, "thanh_long");
        if (thanhLongLvl > 0 && !isOnCooldownKey(player, "thanh_long_orb", 4)) {
            executeActions(List.of("THANH_LONG_ORB"), player, null, player.getLocation(), 0, item, null, thanhLongLvl);
        }

        if (player.isSneaking() && event.getClickedBlock() != null) {
            triggerItemEnchants(item, TriggerType.SNEAK_INTERACT, player, null, event.getClickedBlock().getLocation(), 0, null);
        } else {
            triggerItemEnchants(item, TriggerType.INTERACT, player, null, player.getLocation(), 0, null);
        }
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        for (ItemStack item : getActiveEquipment(player)) {
            if (item.hasItemMeta()) {
                PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();

                int huyenVuLvl = getAppliedLevel(pdc, "huyen_vu");
                if (huyenVuLvl > 0 && !isOnCooldownKey(player, "huyen_vu_shell", 20)) {
                    executeActions(List.of("HUYEN_VU_SHELL"), player, null, player.getLocation(), 0, item, null, huyenVuLvl);
                }

                int tuThanhLvl = getAppliedLevel(pdc, "tu_thanh");
                if (tuThanhLvl > 0 && !isOnCooldownKey(player, "tu_thanh_domain", 25)) {
                    executeActions(List.of("TU_THANH_DOMAIN"), player, null, player.getLocation(), 0, item, null, tuThanhLvl);
                }
            }
            triggerItemEnchants(item, TriggerType.SNEAK, player, null, player.getLocation(), 0, null);
        }
    }

    // =========================================================================
    // VÒNG LẶP BỊ ĐỘNG (CHẠY MỖI 2 GIÂY)
    // =========================================================================

    public void tickPassives() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<ItemStack> equipment = getActiveEquipment(player);
            int gaiaMaxLevel = 0;
            boolean hasHuyenVu = false;

            for (ItemStack item : equipment) {
                if (item.hasItemMeta()) {
                    PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
                    gaiaMaxLevel = Math.max(gaiaMaxLevel, getAppliedLevel(pdc, "giap_gaia"));
                    if (getAppliedLevel(pdc, "huyen_vu") > 0) hasHuyenVu = true;
                }
                triggerItemEnchants(item, TriggerType.PASSIVE, player, null, player.getLocation(), 0, null);
            }

            AttributeInstance maxHpAttr = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHpAttr != null) {
                AttributeModifier existing = maxHpAttr.getModifier(gaiaHpModifierKey);
                double desiredBonus = gaiaMaxLevel > 0 ? (6.0 + gaiaMaxLevel * 2.0) : 0.0;
                if (desiredBonus > 0 && (existing == null || existing.getAmount() != desiredBonus)) {
                    if (existing != null) maxHpAttr.removeModifier(existing);
                    maxHpAttr.addModifier(new AttributeModifier(gaiaHpModifierKey, desiredBonus, AttributeModifier.Operation.ADD_NUMBER));
                } else if (desiredBonus <= 0 && existing != null) {
                    maxHpAttr.removeModifier(existing);
                    if (player.getHealth() > maxHpAttr.getValue()) {
                        player.setHealth(maxHpAttr.getValue());
                    }
                }
            }

            if (hasHuyenVu) {
                Map<PotionEffectType, Integer> playerCaps = boostedBuffCaps.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
                for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                    PotionEffectType type = effect.getType();
                    if (NEGATIVE_EFFECTS.contains(type)) {
                        player.removePotionEffect(type);
                    } else {
                        int curAmp = effect.getAmplifier();
                        Integer alreadyBoostedTo = playerCaps.get(type);
                        if (alreadyBoostedTo == null || curAmp < alreadyBoostedTo) {
                            int targetAmp = Math.min(9, curAmp + 1);
                            playerCaps.put(type, targetAmp);
                            player.addPotionEffect(new PotionEffect(type, effect.getDuration(), targetAmp, effect.isAmbient(), effect.hasParticles()));
                        }
                    }
                }
            } else {
                boostedBuffCaps.remove(player.getUniqueId());
            }
        }
    }

    // =========================================================================
    // BỘ THỰC THI HIỆU ỨNG CÓ TÍNH CẤP ĐỘ (ACTIONS)
    // =========================================================================

    private static class LeveledAction {
        final String action;
        final int level;

        LeveledAction(String action, int level) {
            this.action = action;
            this.level = level;
        }
    }

    private void triggerItemEnchants(ItemStack item, TriggerType trigger, Player self, LivingEntity target, Location impactLoc, double damageDealt, Event rawEvent) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (CustomEnchant enchant : enchants.values()) {
            if (enchant.getTriggerType() != trigger) continue;
            int lvl = getAppliedLevel(pdc, enchant.getId());
            if (lvl <= 0) continue;

            double scaledChance = Math.min(1.0, enchant.getChance() * (1.0 + (lvl - 1) * 0.15));
            if (ThreadLocalRandom.current().nextDouble() > scaledChance) continue;
            if (isOnCooldown(self, enchant)) continue;

            executeActions(enchant.getActions(), self, target, impactLoc, damageDealt, item, rawEvent, lvl);
        }
    }

    private List<LeveledAction> collectLeveledActions(List<ItemStack> items, TriggerType trigger, Player self) {
        List<LeveledAction> list = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) continue;
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            for (CustomEnchant enchant : enchants.values()) {
                if (enchant.getTriggerType() != trigger) continue;
                int lvl = getAppliedLevel(pdc, enchant.getId());
                if (lvl <= 0) continue;

                double scaledChance = Math.min(1.0, enchant.getChance() * (1.0 + (lvl - 1) * 0.15));
                if (ThreadLocalRandom.current().nextDouble() > scaledChance) continue;
                if (isOnCooldown(self, enchant)) continue;
                for (String act : enchant.getActions()) {
                    list.add(new LeveledAction(act, lvl));
                }
            }
        }
        return list;
    }

    private void executeActions(List<String> actions, Player self, LivingEntity target, Location impactLoc, double damageDealt, ItemStack sourceItem, Event rawEvent, int level) {
        Location loc = (impactLoc != null) ? impactLoc : (target != null ? target.getLocation() : self.getLocation());
        double lvlMult = 1.0 + (level - 1) * 0.25;

        for (String raw : actions) {
            String[] parts = raw.split(":");
            String type = parts[0].toUpperCase();

            try {
                switch (type) {
                    case "HUYEN_VU_SHELL" -> {
                        int durationSec = 3 + level;
                        double radius = 4.5 + level * 0.5;
                        long endTime = System.currentTimeMillis() + (durationSec * 1000L);
                        huyenVuShieldUntil.put(self.getUniqueId(), endTime);

                        self.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, durationSec * 20, level, true, false));
                        playSoundSafe(self, "block.beacon.activate", 1.3f);
                        self.sendActionBar(Component.text("🐢 Huyền Vũ " + toRoman(level) + ": Kết Giới Quy Giáp (" + durationSec + "s)!", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));

                        new BukkitRunnable() {
                            int ticks = 0;

                            @Override
                            public void run() {
                                if (!self.isOnline() || ticks >= durationSec * 20) {
                                    huyenVuShieldUntil.remove(self.getUniqueId());
                                    cancel();
                                    return;
                                }
                                Location c = self.getLocation().add(0, 1.0, 0);
                                World w = c.getWorld();

                                for (int angle = 0; angle < 360; angle += 18) {
                                    double rad = Math.toRadians(angle + ticks * 6);
                                    double x = Math.cos(rad) * radius;
                                    double z = Math.sin(rad) * radius;
                                    w.spawnParticle(Particle.FALLING_WATER, c.clone().add(x, 0.8, z), 2, 0.05, 0.3, 0.05, 0);
                                    w.spawnParticle(Particle.SNOWFLAKE, c.clone().add(x, -0.3, z), 1, 0, 0, 0, 0.01);
                                }

                                for (Entity e : w.getNearbyEntities(c, radius, radius, radius)) {
                                    if (e instanceof LivingEntity le && le != self) {
                                        Vector push = le.getLocation().toVector().subtract(c.toVector());
                                        if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
                                        le.setVelocity(push.setY(0).normalize().multiply(1.15).setY(0.35));
                                        le.setFreezeTicks(100);
                                        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2 + level, true, false));
                                    }
                                }
                                ticks += 4;
                            }
                        }.runTaskTimer(plugin, 0L, 4L);
                    }
                    case "TU_THANH_DOMAIN" -> {
                        int durationSec = 4 + level;
                        double radius = 7.0 + level * 1.0;
                        long endTime = System.currentTimeMillis() + (durationSec * 1000L);
                        tuThanhDomainUntil.put(self.getUniqueId(), endTime);

                        self.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, durationSec * 20, 3, true, false));
                        self.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, durationSec * 20, 2 + level, true, false));
                        playSoundSafe(self, "entity.ender_dragon.growl", 1.1f);
                        playSoundSafe(self, "block.end_portal.spawn", 1.2f);
                        self.sendActionBar(Component.text("⚜ Tứ Thánh Hợp Nhất " + toRoman(level) + ": Lãnh Địa Tứ Tượng (" + durationSec + "s)!", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));

                        Location domainCenter = self.getLocation().clone();
                        World w = domainCenter.getWorld();

                        new BukkitRunnable() {
                            int ticks = 0;

                            @Override
                            public void run() {
                                if (!self.isOnline() || ticks >= durationSec * 20) {
                                    tuThanhDomainUntil.remove(self.getUniqueId());
                                    cancel();
                                    return;
                                }

                                // Vẽ vòng tròn Lãnh Địa 4 nguyên tố xung quanh vùng triển khai
                                for (int angle = 0; angle < 360; angle += 15) {
                                    double rad = Math.toRadians(angle + ticks * 5);
                                    Location edge = domainCenter.clone().add(Math.cos(rad) * radius, 0.5, Math.sin(rad) * radius);
                                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, edge, 1, 0, 0.1, 0, 0.01);
                                    w.spawnParticle(Particle.FLAME, edge, 1, 0, 0.1, 0, 0.01);
                                    w.spawnParticle(Particle.ELECTRIC_SPARK, edge.clone().add(0, 0.8, 0), 1, 0, 0, 0, 0.02);
                                }

                                List<LivingEntity> enemies = new ArrayList<>();
                                for (Entity e : w.getNearbyEntities(domainCenter, radius, radius, radius)) {
                                    if (e instanceof LivingEntity le && le != self) {
                                        enemies.add(le);
                                    }
                                }

                                // Mỗi 1 giây (20 ticks): Tứ Tượng luân phiên oanh tạc kẻ địch trong lãnh địa
                                if (ticks % 20 == 0 && !enemies.isEmpty()) {
                                    // 1. Thanh Long giáng sét
                                    LivingEntity boltTarget = enemies.get(ThreadLocalRandom.current().nextInt(enemies.size()));
                                    w.strikeLightningEffect(boltTarget.getLocation());
                                    boltTarget.setHealth(Math.max(0.0, boltTarget.getHealth() - (4.0 + level * 1.5)));
                                    boltTarget.damage(0.01, self);

                                    // 2. Bạch Hổ bão kiếm chém toàn bộ kẻ địch trong vùng
                                    for (LivingEntity le : enemies) {
                                        w.spawnParticle(Particle.SWEEP_ATTACK, le.getLocation().add(0, 1.0, 0), 3, 0.5, 0.3, 0.5, 0);
                                        le.damage(3.0 + level * 1.0, self);
                                        le.setFireTicks(80);
                                    }

                                    // 3. Huyền Vũ hồi máu & giải độc cho chủ nhân
                                    healPlayer(self, 2.0 + level);
                                }
                                ticks += 5;
                            }
                        }.runTaskTimer(plugin, 0L, 5L);
                    }
                    case "THANH_LONG_ORB" -> {
                        Location start = self.getEyeLocation().add(self.getLocation().getDirection().multiply(1.0));
                        Vector dir = self.getEyeLocation().getDirection().normalize();
                        playSoundSafe(self, "entity.illusioner.cast_spell", 1.2f);
                        self.sendActionBar(Component.text("🐉 Thanh Long " + toRoman(level) + ": Quả Cầu Lôi Đình!", NamedTextColor.AQUA, TextDecoration.BOLD));

                        int maxSteps = 18 + level * 4;
                        double boltDmg = 3.5 + level * 1.5;

                        new BukkitRunnable() {
                            int step = 0;
                            final Location current = start.clone();

                            @Override
                            public void run() {
                                if (!self.isOnline() || step >= maxSteps) {
                                    cancel();
                                    return;
                                }
                                current.add(dir.clone().multiply(1.2));
                                World w = current.getWorld();

                                w.spawnParticle(Particle.ELECTRIC_SPARK, current, 20, 0.35, 0.35, 0.35, 0.08);
                                w.spawnParticle(Particle.SOUL_FIRE_FLAME, current, 6, 0.2, 0.2, 0.2, 0.02);

                                if (step % 2 == 0) {
                                    int groundY = w.getHighestBlockYAt(current);
                                    Location strikeLoc = new Location(w, current.getX(), Math.min(current.getY(), groundY + 1), current.getZ());
                                    w.strikeLightningEffect(strikeLoc);

                                    for (int s = 0; s < level + 1; s++) {
                                        double ox = ThreadLocalRandom.current().nextDouble(-2.5, 2.5);
                                        double oz = ThreadLocalRandom.current().nextDouble(-2.5, 2.5);
                                        Location sideStrike = strikeLoc.clone().add(ox, 0, oz);
                                        sideStrike.setY(w.getHighestBlockYAt(sideStrike) + 1);
                                        w.strikeLightningEffect(sideStrike);
                                    }

                                    for (Entity e : w.getNearbyEntities(strikeLoc, 3.8, 4.5, 3.8)) {
                                        if (e instanceof LivingEntity le && le != self) {
                                            le.setHealth(Math.max(0.0, le.getHealth() - boltDmg));
                                            le.damage(0.01, self);
                                        }
                                    }
                                }

                                if (!current.getBlock().isPassable()) {
                                    cancel();
                                    return;
                                }
                                step++;
                            }
                        }.runTaskTimer(plugin, 0L, 1L);
                    }
                    case "CHU_TUOC_FIREBALLS" -> {
                        int baseCount = parts.length > 1 ? Integer.parseInt(parts[1]) : 5;
                        int count = baseCount + (level - 1);
                        Location eye = self.getEyeLocation();
                        Vector baseDir = eye.getDirection().normalize();
                        Vector right = new Vector(-baseDir.getZ(), 0, baseDir.getX()).normalize();

                        playSoundSafe(self, "entity.ghast.shoot", 1.0f);
                        self.sendActionBar(Component.text("🔥 Chu Tước " + toRoman(level) + ": Ngũ Hỏa Cầu Hủy Diệt!", NamedTextColor.GOLD, TextDecoration.BOLD));

                        for (int i = 0; i < count; i++) {
                            double spread = (i - (count - 1) / 2.0) * 0.20;
                            Vector shotDir = baseDir.clone().add(right.clone().multiply(spread)).normalize();
                            Location spawnLoc = eye.clone().add(shotDir.clone().multiply(1.5));

                            LargeFireball fb = self.getWorld().spawn(spawnLoc, LargeFireball.class);
                            fb.setShooter(self);
                            fb.setDirection(shotDir);
                            fb.setVelocity(shotDir.multiply(1.6));
                            fb.setYield(0.0f);
                            fb.setIsIncendiary(false);
                            fb.getPersistentDataContainer().set(unreflectableFireballKey, PersistentDataType.BYTE, (byte) 1);
                            fb.getPersistentDataContainer().set(bookLevelKey, PersistentDataType.INTEGER, level);

                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (fb.isValid()) fb.remove();
                            }, 80L);
                        }
                    }
                    case "BACH_HO_DASH_CLEAVE" -> {
                        if (!isOnCooldownKey(self, "bach_ho_dash", 3)) {
                            Vector dir = self.getLocation().getDirection().setY(0);
                            if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
                            dir.normalize();

                            self.setVelocity(dir.clone().multiply(1.7 + level * 0.15).setY(0.25));
                            playSoundSafe(self, "entity.player.attack.sweep", 1.3f);
                            self.sendActionBar(Component.text("🐅 Bạch Hổ " + toRoman(level) + ": Cuồng Phong Trảm!", NamedTextColor.WHITE, TextDecoration.BOLD));

                            double slashDmg = 4.0 + level * 1.5;
                            new BukkitRunnable() {
                                int ticks = 0;

                                @Override
                                public void run() {
                                    if (!self.isOnline() || ticks >= 3) {
                                        cancel();
                                        return;
                                    }
                                    Location pLoc = self.getLocation().add(0, 1.0, 0);
                                    pLoc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, pLoc, 6, 1.5, 0.4, 1.5, 0);
                                    pLoc.getWorld().spawnParticle(Particle.CLOUD, pLoc, 8, 1.2, 0.3, 1.2, 0.05);

                                    for (Entity nearby : pLoc.getWorld().getNearbyEntities(pLoc, 3.8, 2.5, 3.8)) {
                                        if (nearby instanceof LivingEntity le && le != self) {
                                            le.damage(slashDmg, self);
                                        }
                                    }
                                    ticks++;
                                }
                            }.runTaskTimer(plugin, 1L, 2L);
                        }
                    }
                    case "PHOENIX_SHOCKWAVE" -> {
                        double radius = parts.length > 1 ? Double.parseDouble(parts[1]) : 10.0;
                        double dmg = parts.length > 2 ? Double.parseDouble(parts[2]) : 12.0;
                        Location center = self.getLocation();
                        World w = center.getWorld();

                        w.spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0, 1, 0), 2, 0.5, 0.5, 0.5, 0);
                        playSoundSafe(self, "entity.ender_dragon.growl", 1.2f);
                        playSoundSafe(self, "entity.generic.explode", 0.7f);

                        new BukkitRunnable() {
                            double r = 1.5;

                            @Override
                            public void run() {
                                if (r > radius) {
                                    cancel();
                                    return;
                                }
                                for (int angle = 0; angle < 360; angle += 12) {
                                    double rad = Math.toRadians(angle);
                                    Location pLoc = center.clone().add(Math.cos(rad) * r, 0.6, Math.sin(rad) * r);
                                    w.spawnParticle(Particle.FLAME, pLoc, 2, 0.1, 0.2, 0.1, 0.02);
                                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, pLoc, 1, 0, 0.1, 0, 0.01);
                                }
                                r += 2.2;
                            }
                        }.runTaskTimer(plugin, 0L, 1L);

                        for (Entity nearby : w.getNearbyEntities(center, radius, radius, radius)) {
                            if (nearby instanceof LivingEntity le && le != self) {
                                Vector push = le.getLocation().toVector().subtract(center.toVector());
                                double dist = push.length();
                                if (dist > radius) continue;

                                if (dist < 0.05) {
                                    push = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
                                }
                                push.setY(0).normalize();

                                double remainDist = Math.max(2.0, radius - dist);
                                double speed = Math.min(3.2, 1.3 + remainDist * 0.2);
                                le.setVelocity(push.multiply(speed).setY(0.65));
                                le.setFireTicks(140);
                                le.damage(dmg, self);
                            }
                        }
                    }

                    // --- CÁC HIỆU ỨNG TĂNG TIẾN THEO CẤP ĐỘ ---
                    case "HEAL" -> healPlayer(self, Double.parseDouble(parts[1]) * lvlMult);
                    case "LIFESTEAL" -> healPlayer(self, damageDealt * Double.parseDouble(parts[1]) * lvlMult);
                    case "DAMAGE" -> {
                        if (target != null) target.damage(Double.parseDouble(parts[1]) * lvlMult, self);
                    }
                    case "TRUE_DAMAGE" -> {
                        if (target != null) {
                            double dmg = Double.parseDouble(parts[1]) * lvlMult;
                            target.setHealth(Math.max(0.0, target.getHealth() - dmg));
                            target.damage(0.01, self);
                        }
                    }
                    case "RANDOM_DAMAGE" -> {
                        if (rawEvent instanceof EntityDamageByEntityEvent dmgEvent) {
                            double min = Double.parseDouble(parts[1]);
                            double max = Double.parseDouble(parts[2]);
                            dmgEvent.setDamage(ThreadLocalRandom.current().nextDouble(min, max));
                        }
                    }
                    case "BONUS_DAMAGE_PCT" -> {
                        if (rawEvent instanceof EntityDamageByEntityEvent dmgEvent) {
                            double pct = Double.parseDouble(parts[1]) * lvlMult;
                            dmgEvent.setDamage(dmgEvent.getDamage() * (1.0 + pct));
                        }
                    }
                    case "EXECUTE" -> {
                        if (target != null) {
                            double threshold = Double.parseDouble(parts[1]) + (level - 1) * 0.03;
                            AttributeInstance attr = target.getAttribute(Attribute.MAX_HEALTH);
                            double max = attr != null ? attr.getValue() : 20.0;
                            if (target.getHealth() > 0 && target.getHealth() <= max * threshold) {
                                target.setHealth(0.0);
                                target.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);
                                self.sendActionBar(Component.text("☠ TRẢM HỒN: Kết liễu mục tiêu!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
                            }
                        }
                    }
                    case "SHRED_ARMOR" -> {
                        if (target != null && target.getEquipment() != null) {
                            int loss = Integer.parseInt(parts[1]) * level;
                            ItemStack[] armors = target.getEquipment().getArmorContents();
                            for (ItemStack a : armors) {
                                if (a != null && a.getItemMeta() instanceof Damageable dmg) {
                                    dmg.setDamage(dmg.getDamage() + loss);
                                    a.setItemMeta(dmg);
                                }
                            }
                            target.getEquipment().setArmorContents(armors);
                        }
                    }
                    case "STRIP_BUFFS" -> {
                        if (target != null) {
                            target.removePotionEffect(PotionEffectType.RESISTANCE);
                            target.removePotionEffect(PotionEffectType.ABSORPTION);
                        }
                    }
                    case "CLEANSE" -> {
                        for (PotionEffect ef : new ArrayList<>(self.getActivePotionEffects())) {
                            if (NEGATIVE_EFFECTS.contains(ef.getType())) {
                                self.removePotionEffect(ef.getType());
                            }
                        }
                    }
                    case "REPAIR" -> {
                        if (sourceItem != null && sourceItem.getItemMeta() instanceof Damageable dmg && dmg.getDamage() > 0) {
                            dmg.setDamage(Math.max(0, dmg.getDamage() - Integer.parseInt(parts[1]) * level));
                            sourceItem.setItemMeta(dmg);
                        }
                    }
                    case "UNBREAKABLE" -> {
                        if (sourceItem != null && sourceItem.getItemMeta() instanceof Damageable dmg && dmg.getDamage() > 0) {
                            dmg.setDamage(0);
                            sourceItem.setItemMeta(dmg);
                        }
                    }
                    case "REFLECT_DAMAGE" -> {
                        if (target != null) {
                            double ratio = Double.parseDouble(parts[1]) * lvlMult;
                            target.damage(damageDealt * ratio, self);
                        }
                    }
                    case "DEFLECT_PROJECTILE" -> {
                        if (rawEvent instanceof EntityDamageByEntityEvent dmgEvent && dmgEvent.getDamager() instanceof Projectile proj) {
                            dmgEvent.setCancelled(true);
                            Vector vel = proj.getVelocity().multiply(-1.2);
                            proj.setVelocity(vel);
                            proj.setShooter(self);
                        }
                    }
                    case "REVIVE" -> {
                        double ratio = Double.parseDouble(parts[1]);
                        AttributeInstance attr = self.getAttribute(Attribute.MAX_HEALTH);
                        double max = attr != null ? attr.getValue() : 20.0;
                        self.setHealth(Math.min(max, max * ratio));
                        self.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, self.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.2);
                        playSoundSafe(self, "item.totem.use", 1.0f);
                        self.sendMessage(Component.text("🔥 Phượng Hoàng Niết Bàn đã cứu bạn khỏi cửa tử!", NamedTextColor.GOLD, TextDecoration.BOLD));
                    }
                    case "CLEAVE" -> {
                        double radius = Double.parseDouble(parts[1]) + (level - 1) * 0.5;
                        double dmg = Double.parseDouble(parts[2]) * lvlMult;
                        for (Entity nearby : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
                            if (nearby instanceof LivingEntity le && le != self && le != target) {
                                le.damage(dmg, self);
                            }
                        }
                    }
                    case "FIRE_AURA" -> {
                        double radius = Double.parseDouble(parts[1]) + (level - 1) * 0.5;
                        int igniteTicks = Integer.parseInt(parts[2]);
                        double dmg = Double.parseDouble(parts[3]) * lvlMult;
                        World w = self.getWorld();
                        for (int angle = 0; angle < 360; angle += 30) {
                            double rad = Math.toRadians(angle);
                            w.spawnParticle(Particle.FLAME, self.getLocation().add(Math.cos(rad) * radius, 0.5, Math.sin(rad) * radius), 1, 0, 0, 0, 0.01);
                        }
                        for (Entity nearby : w.getNearbyEntities(self.getLocation(), radius, radius, radius)) {
                            if (nearby instanceof LivingEntity le && le != self) {
                                le.setFireTicks(igniteTicks);
                                le.damage(dmg, self);
                            }
                        }
                    }
                    case "SONIC_BOOM" -> {
                        if (target != null) {
                            double dmg = Double.parseDouble(parts[1]) * lvlMult;
                            Location start = self.getEyeLocation();
                            Vector dir = target.getEyeLocation().toVector().subtract(start.toVector());
                            double dist = Math.min(15.0, dir.length());
                            dir.normalize();
                            for (double d = 1.0; d <= dist; d += 1.0) {
                                start.getWorld().spawnParticle(Particle.SONIC_BOOM, start.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
                            }
                            playSoundSafe(self, "entity.warden.sonic_boom", 1.0f);
                            target.setHealth(Math.max(0.0, target.getHealth() - dmg));
                            target.damage(0.01, self);
                            target.setVelocity(dir.multiply(1.4).setY(0.4));
                        }
                    }
                    case "LIGHTNING" -> loc.getWorld().strikeLightning(loc);
                    case "LIGHTNING_EFFECT" -> loc.getWorld().strikeLightningEffect(loc);
                    case "SELF_LIGHTNING" -> self.getWorld().strikeLightning(self.getLocation());
                    case "IGNITE" -> {
                        if (target != null) target.setFireTicks(Integer.parseInt(parts[1]) + (level - 1) * 20);
                    }
                    case "SELF_IGNITE" -> self.setFireTicks(Integer.parseInt(parts[1]));
                    case "FREEZE" -> {
                        if (target != null) target.setFreezeTicks(Integer.parseInt(parts[1]) + (level - 1) * 30);
                    }
                    case "SAFE_EXPLODE" -> {
                        float power = Float.parseFloat(parts[1]) + (level - 1) * 0.4f;
                        double dmg = (parts.length > 2 ? Double.parseDouble(parts[2]) : 4.0) * lvlMult;
                        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc.clone().add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0);
                        playSoundSafe(self, "entity.generic.explode", 1.0f);
                        for (Entity nearby : loc.getWorld().getNearbyEntities(loc, power + 1.5, power + 1.5, power + 1.5)) {
                            if (nearby instanceof LivingEntity le && le != self) {
                                le.damage(dmg, self);
                                Vector push = le.getLocation().toVector().subtract(loc.toVector());
                                if (push.lengthSquared() > 0.01) {
                                    le.setVelocity(push.normalize().multiply(1.2).setY(0.45));
                                }
                            }
                        }
                    }
                    case "SELF_DESTRUCT" -> {
                        if (sourceItem != null) {
                            self.getWorld().spawnParticle(Particle.EXPLOSION, self.getLocation().add(0, 1, 0), 4, 0.5, 0.5, 0.5, 0);
                            playSoundSafe(self, "entity.generic.explode", 0.8f);
                            sourceItem.setAmount(0);
                            Bukkit.broadcast(Component.text("💥 Món đồ của " + self.getName() + " vừa kích hoạt lời nguyền Tự Hủy và nổ tung!", NamedTextColor.RED));
                        }
                    }
                    case "INSTANT_DEATH" -> {
                        Bukkit.broadcast(Component.text("💀 " + self.getName() + " vừa lăn ra đột tử vì lời nguyền Tự Die!", NamedTextColor.DARK_RED));
                        self.setHealth(0.0);
                    }
                    case "SELF_DAMAGE" -> self.damage(Double.parseDouble(parts[1]));
                    case "DROP_HAND" -> {
                        ItemStack hand = self.getInventory().getItemInMainHand();
                        if (!hand.getType().isAir()) {
                            self.getWorld().dropItemNaturally(self.getLocation().add(0, 1, 0), hand.clone());
                            self.getInventory().setItemInMainHand(null);
                            self.sendActionBar(Component.text("✖ Trượt Tay: Rơi mất vũ khí!", NamedTextColor.RED));
                        }
                    }
                    case "FAKE_DURABILITY" -> {
                        if (sourceItem != null && sourceItem.getItemMeta() instanceof Damageable dmg) {
                            if (ThreadLocalRandom.current().nextDouble() < 0.02) {
                                sourceItem.setAmount(0);
                                playSoundSafe(self, "entity.item.break", 1.0f);
                                self.sendActionBar(Component.text("✖ Đồ Fake kém chất lượng đã vỡ vụn!", NamedTextColor.RED));
                            } else {
                                dmg.setDamage(dmg.getDamage() + 25);
                                sourceItem.setItemMeta(dmg);
                            }
                        }
                    }
                    case "BROKEN_LEG" -> {
                        if (ThreadLocalRandom.current().nextBoolean()) {
                            self.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, true, false));
                            self.sendActionBar(Component.text("🦴 Trẹo chân rồi!", NamedTextColor.RED));
                        } else {
                            self.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 2, true, false));
                            self.sendActionBar(Component.text("⚡ Chạy bán sống bán chết!", NamedTextColor.AQUA));
                        }
                    }
                    case "RANDOM_TP" -> {
                        double r = Double.parseDouble(parts[1]);
                        double dx = ThreadLocalRandom.current().nextDouble(-r, r);
                        double dz = ThreadLocalRandom.current().nextDouble(-r, r);
                        Location dest = self.getLocation().clone().add(dx, 0, dz);
                        dest.setY(dest.getWorld().getHighestBlockYAt(dest) + 1);
                        self.teleport(dest);
                        playSoundSafe(self, "entity.enderman.teleport", 1.0f);
                    }
                    case "SWAP_POS" -> {
                        if (target != null) {
                            Location pLoc = self.getLocation().clone();
                            Location tLoc = target.getLocation().clone();
                            self.teleport(tLoc);
                            target.teleport(pLoc);
                            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
                            playSoundSafe(self, "entity.enderman.teleport", 1.2f);
                        }
                    }
                    case "TELEPORT_BEHIND" -> {
                        double range = Double.parseDouble(parts[1]) + (level - 1) * 4.0;
                        RayTraceResult ray = self.getWorld().rayTraceEntities(self.getEyeLocation(), self.getLocation().getDirection(), range, e -> e instanceof LivingEntity && e != self);
                        if (ray != null && ray.getHitEntity() instanceof LivingEntity hit) {
                            Vector back = hit.getLocation().getDirection().setY(0).normalize().multiply(-1.5);
                            Location dest = hit.getLocation().clone().add(back);
                            dest.setYaw(hit.getLocation().getYaw());
                            dest.setPitch(self.getLocation().getPitch());
                            self.teleport(dest);
                            playSoundSafe(self, "entity.enderman.teleport", 1.2f);
                        }
                    }
                    case "BLINK" -> {
                        double range = Double.parseDouble(parts[1]) + (level - 1) * 4.0;
                        Vector dir = self.getLocation().getDirection().normalize();
                        Location safe = self.getLocation().clone();
                        for (int i = 1; i <= (int) range; i++) {
                            Location step = self.getLocation().clone().add(dir.clone().multiply(i));
                            if (step.getBlock().isPassable() && step.clone().add(0, 1, 0).getBlock().isPassable()) {
                                safe = step;
                            } else {
                                break;
                            }
                        }
                        self.teleport(safe);
                        playSoundSafe(self, "entity.enderman.teleport", 1.3f);
                    }
                    case "DASH" -> {
                        double power = Double.parseDouble(parts[1]) + (level - 1) * 0.35;
                        Vector dir = self.getLocation().getDirection().normalize().multiply(power);
                        self.setVelocity(dir.setY(0.35));
                    }
                    case "LAUNCH" -> {
                        if (target != null) target.setVelocity(target.getVelocity().setY(Double.parseDouble(parts[1]) * lvlMult));
                    }
                    case "PULL_MOBS" -> {
                        double r = Double.parseDouble(parts[1]);
                        for (Entity nearby : self.getWorld().getNearbyEntities(self.getLocation(), r, r, r)) {
                            if (nearby instanceof Monster monster) {
                                Vector pull = self.getLocation().toVector().subtract(monster.getLocation().toVector()).normalize().multiply(0.9);
                                monster.setVelocity(pull.setY(0.3));
                            }
                        }
                    }
                    case "BLACK_HOLE" -> {
                        double radius = Double.parseDouble(parts[1]) + (level - 1) * 1.0;
                        int sec = Integer.parseInt(parts[2]) + (level - 1);
                        Location center = loc.clone();
                        new BukkitRunnable() {
                            int ticks = 0;
                            @Override
                            public void run() {
                                if (ticks >= sec * 20) {
                                    cancel();
                                    return;
                                }
                                center.getWorld().spawnParticle(Particle.PORTAL, center.clone().add(0, 1, 0), 15, radius * 0.4, 0.5, radius * 0.4, 0.1);
                                for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                                    if (e instanceof LivingEntity le && le != self) {
                                        Vector pull = center.toVector().subtract(le.getLocation().toVector());
                                        if (pull.lengthSquared() > 0.2) {
                                            le.setVelocity(pull.normalize().multiply(0.45));
                                        }
                                        if (ticks % 20 == 0) {
                                            le.damage(2.0 * lvlMult, self);
                                        }
                                    }
                                }
                                ticks += 5;
                            }
                        }.runTaskTimer(plugin, 0L, 5L);
                    }
                    case "SWORD_BARRAGE" -> {
                        int count = Integer.parseInt(parts[1]) + (level - 1) * 6;
                        double dmg = (parts.length > 2 ? Double.parseDouble(parts[2]) : 6.0) + (level - 1) * 1.5;
                        playSoundSafe(self, "entity.illusioner.cast_spell", 1.4f);

                        new BukkitRunnable() {
                            int fired = 0;
                            @Override
                            public void run() {
                                if (!self.isOnline() || fired >= count) {
                                    cancel();
                                    return;
                                }
                                Vector dir = self.getEyeLocation().getDirection().normalize();
                                Vector right = new Vector(-dir.getZ(), 0, dir.getX()).normalize();
                                Location eye = self.getEyeLocation();

                                for (int b = 0; b < 3 && fired < count; b++) {
                                    double sideOffset = ThreadLocalRandom.current().nextDouble(-1.4, 1.4);
                                    double heightOffset = ThreadLocalRandom.current().nextDouble(-0.3, 0.9);
                                    Location spawnLoc = eye.clone()
                                            .add(right.clone().multiply(sideOffset))
                                            .add(0, heightOffset, 0)
                                            .add(dir.clone().multiply(0.8));

                                    Arrow swordProj = self.getWorld().spawnArrow(spawnLoc, dir, 3.2f, 1.5f);
                                    swordProj.setShooter(self);
                                    swordProj.setDamage(dmg);
                                    swordProj.setPierceLevel(2);
                                    swordProj.setCritical(true);
                                    swordProj.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);

                                    spawnLoc.getWorld().spawnParticle(Particle.SWEEP_ATTACK, spawnLoc, 1, 0, 0, 0, 0);
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        if (swordProj.isValid()) swordProj.remove();
                                    }, 50L);
                                    fired++;
                                }
                                playSoundSafe(self, "entity.player.attack.sweep", 1.5f);
                            }
                        }.runTaskTimer(plugin, 0L, 2L);
                    }
                    case "ARROW_RAIN" -> {
                        int count = Integer.parseInt(parts[1]) * level;
                        Block targetBlock = self.getTargetBlockExact(15);
                        Location center = targetBlock != null ? targetBlock.getLocation().add(0.5, 1, 0.5) : self.getLocation().add(self.getLocation().getDirection().multiply(6));
                        List<Arrow> spawned = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            double ox = ThreadLocalRandom.current().nextDouble(-3.0, 3.0);
                            double oz = ThreadLocalRandom.current().nextDouble(-3.0, 3.0);
                            Location spawnLoc = center.clone().add(ox, 9.0 + (i * 0.4), oz);
                            Arrow arrow = center.getWorld().spawnArrow(spawnLoc, new Vector(0, -1.6, 0), 2.0f, 3.0f);
                            arrow.setShooter(self);
                            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                            spawned.add(arrow);
                        }
                        Bukkit.getScheduler().runTaskLater(plugin, () -> spawned.forEach(a -> { if (a.isValid()) a.remove(); }), 60L);
                    }
                    case "MULTI_SHOT" -> {
                        int extra = Integer.parseInt(parts[1]) + (level - 1);
                        for (int i = 1; i <= extra; i++) {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (!self.isOnline()) return;
                                Arrow arrow = self.launchProjectile(Arrow.class);
                                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (arrow.isValid()) arrow.remove(); }, 60L);
                            }, i * 3L);
                        }
                    }
                    case "DRAGON_BREATH" -> {
                        int ticks = Integer.parseInt(parts[1]) + (level - 1) * 40;
                        Block targetBlock = self.getTargetBlockExact(12 + (level - 1) * 4);
                        Location spawnLoc = targetBlock != null ? targetBlock.getLocation().add(0.5, 1.1, 0.5) : self.getLocation().add(self.getLocation().getDirection().multiply(4));
                        AreaEffectCloud cloud = (AreaEffectCloud) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.AREA_EFFECT_CLOUD);
                        cloud.setSource(self);
                        cloud.setParticle(Particle.DRAGON_BREATH);
                        cloud.setRadius(2.5f + level * 0.5f);
                        cloud.setDuration(ticks);
                        PotionEffectType wither = Registry.EFFECT.get(NamespacedKey.minecraft("wither"));
                        if (wither != null) {
                            cloud.addCustomEffect(new PotionEffect(wither, 40, level), true);
                        }
                    }
                    case "STEAL_XP" -> {
                        int amount = Integer.parseInt(parts[1]) * level;
                        if (target instanceof Player victimPlayer) {
                            int currentTotal = getTotalExperience(victimPlayer);
                            int stolen = Math.min(currentTotal, amount);
                            if (stolen > 0) {
                                setTotalExperience(victimPlayer, currentTotal - stolen);
                                self.giveExp(stolen);
                                self.sendActionBar(Component.text("✦ Hấp Tinh: Hút +" + stolen + " XP!", NamedTextColor.LIGHT_PURPLE));
                            }
                        } else if (target != null) {
                            self.giveExp(amount);
                        }
                    }
                    case "STEAL_ITEM" -> {
                        if (target instanceof Player victimPlayer) {
                            PlayerInventory inv = victimPlayer.getInventory();
                            List<Integer> validSlots = new ArrayList<>();
                            for (int i = 0; i < 36; i++) {
                                ItemStack st = inv.getItem(i);
                                if (st != null && !st.getType().isAir() && !hasAction(st, "SOULBOUND")) {
                                    validSlots.add(i);
                                }
                            }
                            if (!validSlots.isEmpty()) {
                                int slot = validSlots.get(ThreadLocalRandom.current().nextInt(validSlots.size()));
                                ItemStack stolen = inv.getItem(slot).clone();
                                inv.setItem(slot, null);
                                Map<Integer, ItemStack> rem = self.getInventory().addItem(stolen);
                                rem.values().forEach(r -> self.getWorld().dropItemNaturally(self.getLocation(), r));
                                self.sendMessage(Component.text("🕵 Bạn vừa móc túi trộm được " + stolen.getType().name() + " từ " + victimPlayer.getName() + "!", NamedTextColor.GOLD));
                                victimPlayer.sendMessage(Component.text("⚠ Bạn vừa bị " + self.getName() + " móc túi mất đồ!", NamedTextColor.RED));
                            }
                        } else if (target != null) {
                            target.getWorld().dropItemNaturally(target.getLocation(), new ItemStack(Material.EMERALD, level));
                        }
                    }
                    case "FEED" -> {
                        self.setFoodLevel(Math.min(20, self.getFoodLevel() + Integer.parseInt(parts[1])));
                        self.setSaturation(Math.min(20f, self.getSaturation() + Float.parseFloat(parts[2])));
                    }
                    case "SCAN_GLOW" -> {
                        double r = Double.parseDouble(parts[1]) + (level - 1) * 6.0;
                        int ticks = Integer.parseInt(parts[2]);
                        for (Entity e : self.getWorld().getNearbyEntities(self.getLocation(), r, r, r)) {
                            if (e instanceof LivingEntity le && le != self) {
                                le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, true, false));
                            }
                        }
                    }
                    case "GHAST_SCREAM" -> {
                        playSoundSafe(self, "entity.ghast.scream", 1.0f);
                        Bukkit.broadcast(Component.text("<" + self.getName() + "> ", NamedTextColor.WHITE)
                                .append(Component.text("Á á á cứu tui với, có người đánh tui!!", NamedTextColor.YELLOW)));
                    }
                    case "WATER_BUFF" -> {
                        if (self.isInWater() || self.getWorld().hasStorm()) {
                            applyPotion(self, parts, level);
                        }
                    }
                    case "AQUA_CURSE" -> {
                        if (self.isInWater()) {
                            self.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 80, 0, true, false));
                            self.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 80, 0, true, false));
                        } else {
                            self.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, true, false));
                        }
                    }
                    case "UNDERGROUND_BUFF" -> {
                        int maxY = Integer.parseInt(parts[1]);
                        if (self.getLocation().getBlockY() < maxY) {
                            PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(parts[2].toLowerCase()));
                            if (effectType != null) {
                                int baseLvl = Integer.parseInt(parts[3]);
                                int amp = Math.max(0, (baseLvl + (level - 1)) - 1);
                                int ticks = Integer.parseInt(parts[4]);
                                self.addPotionEffect(new PotionEffect(effectType, ticks, amp, true, false));
                            }
                        }
                    }
                    case "TUNNEL_1X2" -> {
                        int len = Integer.parseInt(parts[1]) + (level - 1) * 3;
                        BlockFace facing = self.getFacing();
                        Block start = loc.getBlock();
                        miningLock.add(self.getUniqueId());
                        try {
                            for (int i = 0; i < len; i++) {
                                Block b1 = start.getRelative(facing, i);
                                Block b2 = b1.getRelative(BlockFace.UP);
                                for (Block b : List.of(b1, b2)) {
                                    if (!b.getType().isAir() && !b.isLiquid() && b.getType().getHardness() >= 0) {
                                        b.breakNaturally(sourceItem);
                                    }
                                }
                            }
                        } finally {
                            miningLock.remove(self.getUniqueId());
                        }
                    }
                    case "POTION_SELF" -> applyPotion(self, parts, level);
                    case "POTION_TARGET" -> {
                        if (target != null) applyPotion(target, parts, level);
                    }
                    case "SOUND" -> playSoundSafe(self, parts[1].toLowerCase(), 1.0f);
                    case "PARTICLE" -> {
                        Particle particle = Particle.valueOf(parts[1].toUpperCase());
                        int count = parts.length > 2 ? Integer.parseInt(parts[2]) : 10;
                        loc.getWorld().spawnParticle(particle, loc.clone().add(0, 1, 0), count, 0.4, 0.4, 0.4, 0.05);
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

    // =========================================================================
    // CÁC HÀM TIỆN ÍCH
    // =========================================================================

    private List<ItemStack> getActiveEquipment(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                items.add(armor);
            }
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!main.getType().isAir() && main.hasItemMeta()) {
            items.add(main);
        }
        return items;
    }

    private boolean hasAction(ItemStack item, String actionPrefix) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (CustomEnchant enchant : enchants.values()) {
            if (getAppliedLevel(pdc, enchant.getId()) > 0) {
                for (String act : enchant.getActions()) {
                    if (act.toUpperCase().startsWith(actionPrefix)) return true;
                }
            }
        }
        return false;
    }

    private boolean isOnCooldown(Player player, CustomEnchant enchant) {
        if (enchant.getCooldownSeconds() <= 0) return false;
        return isOnCooldownKey(player, enchant.getId(), enchant.getCooldownSeconds());
    }

    private boolean isOnCooldownKey(Player player, String key, int seconds) {
        if (seconds <= 0) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> playerCd = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long readyAt = playerCd.getOrDefault(key, 0L);
        if (now < readyAt) return true;
        playerCd.put(key, now + (seconds * 1000L));
        return false;
    }

    private void healPlayer(Player player, double amount) {
        if (amount <= 0) return;
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double max = (attr != null) ? attr.getValue() : 20.0;
        player.setHealth(Math.min(max, player.getHealth() + amount));
    }

    private void applyPotion(LivingEntity entity, String[] parts, int enchantLevel) {
        PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(parts[1].toLowerCase()));
        if (effectType != null) {
            int baseLvl = Integer.parseInt(parts[2]);
            int finalLvl = (baseLvl >= 200) ? baseLvl : (baseLvl + (enchantLevel - 1));
            int amp = Math.max(0, finalLvl - 1);
            int ticks = Integer.parseInt(parts[3]);
            entity.addPotionEffect(new PotionEffect(effectType, ticks, amp, true, false));
        }
    }

    private void playSoundSafe(Player player, String key, float pitch) {
        Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(key));
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0f, pitch);
        }
    }

    private List<Block> get3x3Blocks(Block center, BlockFace face) {
        List<Block> blocks = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block rel = switch (face) {
                    case UP, DOWN -> center.getRelative(a, 0, b);
                    case NORTH, SOUTH -> center.getRelative(a, b, 0);
                    default -> center.getRelative(0, b, a);
                };
                blocks.add(rel);
            }
        }
        return blocks;
    }

    private boolean isVeinBlock(Material mat) {
        String name = mat.name();
        return name.endsWith("_ORE") || mat == Material.ANCIENT_DEBRIS || name.endsWith("_LOG") || name.endsWith("_STEM");
    }

    private boolean isOreBlock(Material mat) {
        return mat.name().endsWith("_ORE") || mat == Material.ANCIENT_DEBRIS;
    }

    private Material getOreBlockForm(Material ore) {
        String name = ore.name();
        if (name.contains("COAL")) return Material.COAL_BLOCK;
        if (name.contains("IRON")) return Material.IRON_BLOCK;
        if (name.contains("COPPER")) return Material.COPPER_BLOCK;
        if (name.contains("GOLD")) return Material.GOLD_BLOCK;
        if (name.contains("DIAMOND")) return Material.DIAMOND_BLOCK;
        if (name.contains("EMERALD")) return Material.EMERALD_BLOCK;
        if (name.contains("REDSTONE")) return Material.REDSTONE_BLOCK;
        if (name.contains("LAPIS")) return Material.LAPIS_BLOCK;
        if (name.contains("QUARTZ")) return Material.QUARTZ_BLOCK;
        if (ore == Material.ANCIENT_DEBRIS) return Material.NETHERITE_BLOCK;
        return ore;
    }

    private Material getOreIngotForm(Material ore) {
        String name = ore.name();
        if (name.contains("COAL")) return Material.COAL;
        if (name.contains("IRON")) return Material.IRON_INGOT;
        if (name.contains("COPPER")) return Material.COPPER_INGOT;
        if (name.contains("GOLD")) return Material.GOLD_INGOT;
        if (name.contains("DIAMOND")) return Material.DIAMOND;
        if (name.contains("EMERALD")) return Material.EMERALD;
        if (name.contains("REDSTONE")) return Material.REDSTONE;
        if (name.contains("LAPIS")) return Material.LAPIS_LAZULI;
        if (name.contains("QUARTZ")) return Material.QUARTZ;
        if (ore == Material.ANCIENT_DEBRIS) return Material.NETHERITE_SCRAP;
        return ore;
    }

    private Set<Block> getVeinBlocks(Block start, int limit) {
        Set<Block> visited = new LinkedHashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        Material targetMat = start.getType();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && visited.size() < limit) {
            Block current = queue.poll();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block neighbor = current.getRelative(x, y, z);
                        if (!visited.contains(neighbor) && neighbor.getType() == targetMat) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                            if (visited.size() >= limit) break;
                        }
                    }
                }
            }
        }
        return visited;
    }

    private ItemStack smeltItem(ItemStack input) {
        Iterator<Recipe> iter = Bukkit.recipeIterator();
        while (iter.hasNext()) {
            Recipe recipe = iter.next();
            if (recipe instanceof FurnaceRecipe furnace && furnace.getInputChoice().test(input)) {
                ItemStack result = furnace.getResult().clone();
                result.setAmount(result.getAmount() * input.getAmount());
                return result;
            }
        }
        return input;
    }

    private void damageTool(ItemStack tool) {
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (ThreadLocalRandom.current().nextDouble() > (1.0 / (unbreaking + 1))) return;
        if (tool.getItemMeta() instanceof Damageable dmg) {
            dmg.setDamage(dmg.getDamage() + 1);
            tool.setItemMeta(dmg);
        }
    }

    private int parseIntSafe(String str, int fallback) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int getTotalExperience(Player player) {
        int level = player.getLevel();
        int xp;
        if (level <= 16) xp = level * level + 6 * level;
        else if (level <= 31) xp = (int) (2.5 * level * level - 40.5 * level + 360);
        else xp = (int) (4.5 * level * level - 162.5 * level + 2220);
        return xp + Math.round(player.getExp() * player.getExpToLevel());
    }

    private void setTotalExperience(Player player, int amount) {
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);
        if (amount > 0) player.giveExp(amount);
    }
}
