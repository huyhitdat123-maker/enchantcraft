package com.enchantcraft;

import com.enchantcraft.enchants.CustomEnchant;
import com.enchantcraft.enchants.CustomEnchant.TargetType;
import com.enchantcraft.enchants.CustomEnchant.TriggerType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
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
    private final NamespacedKey arrowEnchantsKey;
    private final Map<String, CustomEnchant> enchants = new LinkedHashMap<>();
    private final List<NamespacedKey> registeredRecipes = new ArrayList<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, BlockFace> lastClickedFace = new HashMap<>();
    private final Set<UUID> miningLock = new HashSet<>();

    public EnchantManager(EnchantCraftPlugin plugin) {
        this.plugin = plugin;
        this.bookEnchantKey = new NamespacedKey(plugin, "enchantcraft_book_id");
        this.arrowEnchantsKey = new NamespacedKey(plugin, "enchantcraft_arrow_ids");
    }

    public void loadEnchants() {
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
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack bow = event.getBow();
        if (bow == null || !bow.hasItemMeta()) return;

        PersistentDataContainer pdc = bow.getItemMeta().getPersistentDataContainer();
        List<String> ids = new ArrayList<>();
        for (CustomEnchant enchant : enchants.values()) {
            if (pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) {
                ids.add(enchant.getId());
            }
        }
        if (!ids.isEmpty()) {
            event.getProjectile().getPersistentDataContainer().set(
                    arrowEnchantsKey, PersistentDataType.STRING, String.join(",", ids)
            );
        }
    }

    @EventHandler
    public void onProjectileHitBlock(ProjectileHitEvent event) {
        if (event.getHitEntity() != null) return;
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player shooter)) return;

        String rawIds = arrow.getPersistentDataContainer().get(arrowEnchantsKey, PersistentDataType.STRING);
        if (rawIds == null || rawIds.isEmpty()) return;

        for (String id : rawIds.split(",")) {
            CustomEnchant enchant = getEnchant(id);
            if (enchant == null || enchant.getTriggerType() != TriggerType.ATTACK) continue;
            if (ThreadLocalRandom.current().nextDouble() > enchant.getChance()) continue;
            if (isOnCooldown(shooter, enchant)) continue;
            executeActions(enchant.getActions(), shooter, null, arrow.getLocation(), 0);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // 1. Tấn công cận chiến bằng vũ khí
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity target) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (!weapon.getType().isAir() && weapon.hasItemMeta()) {
                triggerItemEnchants(weapon, TriggerType.ATTACK, attacker, target, target.getLocation(), event.getFinalDamage());
            }
        }

        // 2. Bắn trúng mục tiêu bằng Cung / Nỏ
        if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter && event.getEntity() instanceof LivingEntity target) {
            String rawIds = arrow.getPersistentDataContainer().get(arrowEnchantsKey, PersistentDataType.STRING);
            if (rawIds != null && !rawIds.isEmpty()) {
                for (String id : rawIds.split(",")) {
                    CustomEnchant enchant = getEnchant(id);
                    if (enchant == null || enchant.getTriggerType() != TriggerType.ATTACK) continue;
                    if (ThreadLocalRandom.current().nextDouble() > enchant.getChance()) continue;
                    if (isOnCooldown(shooter, enchant)) continue;
                    executeActions(enchant.getActions(), shooter, target, target.getLocation(), event.getFinalDamage());
                }
            }
        }

        // 3. Phòng thủ khi mặc giáp
        if (event.getEntity() instanceof Player victim) {
            LivingEntity attacker = (event.getDamager() instanceof LivingEntity le) ? le : null;
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                    triggerItemEnchants(armor, TriggerType.DEFEND, victim, attacker, victim.getLocation(), event.getFinalDamage());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (miningLock.contains(player.getUniqueId())) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType().isAir() || !tool.hasItemMeta()) return;

        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        List<String> activeActions = new ArrayList<>();

        for (CustomEnchant enchant : enchants.values()) {
            if (enchant.getTriggerType() != TriggerType.MINE) continue;
            if (!pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) continue;
            if (ThreadLocalRandom.current().nextDouble() > enchant.getChance()) continue;
            if (isOnCooldown(player, enchant)) continue;
            activeActions.addAll(enchant.getActions());
        }

        if (activeActions.isEmpty()) return;

        boolean mine3x3 = false;
        int veinLimit = 0;
        boolean autoSmelt = false;
        boolean magnet = false;
        int expBoost = 0;
        List<String> normalActions = new ArrayList<>();

        for (String act : activeActions) {
            String[] parts = act.split(":");
            switch (parts[0].toUpperCase()) {
                case "MINE_3X3" -> mine3x3 = true;
                case "VEIN_MINE" -> veinLimit = parts.length > 1 ? Integer.parseInt(parts[1]) : 16;
                case "AUTO_SMELT" -> autoSmelt = true;
                case "MAGNET" -> magnet = true;
                case "EXP_BOOST" -> expBoost += parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                default -> normalActions.add(act);
            }
        }

        Block origin = event.getBlock();
        Set<Block> targetBlocks = new LinkedHashSet<>();
        targetBlocks.add(origin);

        if (mine3x3) {
            targetBlocks.addAll(get3x3Blocks(origin, lastClickedFace.getOrDefault(player.getUniqueId(), BlockFace.NORTH)));
        }
        if (veinLimit > 0 && isVeinBlock(origin.getType())) {
            targetBlocks.addAll(getVeinBlocks(origin, veinLimit));
        }

        // Xử lý block chính và các block mở rộng
        if (autoSmelt || magnet || targetBlocks.size() > 1) {
            event.setDropItems(false);
            miningLock.add(player.getUniqueId());
            try {
                for (Block b : targetBlocks) {
                    if (b.getType().isAir() || b.isLiquid() || b.getType().getHardness() < 0) continue;
                    Collection<ItemStack> drops = b.getDrops(tool, player);

                    if (b != origin) {
                        b.getWorld().playEffect(b.getLocation(), Effect.STEP_SOUND, b.getBlockData());
                        b.setType(Material.AIR);
                        damageTool(tool);
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

        if (!normalActions.isEmpty()) {
            executeActions(normalActions, player, null, origin.getLocation(), 0);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getBlockFace() != null) {
            lastClickedFace.put(event.getPlayer().getUniqueId(), event.getBlockFace());
        }

        if (!event.getAction().isRightClick()) return;
        ItemStack item = event.getItem();
        if (item != null && !item.getType().isAir() && item.hasItemMeta()) {
            triggerItemEnchants(item, TriggerType.INTERACT, event.getPlayer(), null, event.getPlayer().getLocation(), 0);
        }
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                triggerItemEnchants(armor, TriggerType.SNEAK, player, null, player.getLocation(), 0);
            }
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!hand.getType().isAir() && hand.hasItemMeta()) {
            triggerItemEnchants(hand, TriggerType.SNEAK, player, null, player.getLocation(), 0);
        }
    }

    public void tickPassives() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir() && armor.hasItemMeta()) {
                    triggerItemEnchants(armor, TriggerType.PASSIVE, player, null, player.getLocation(), 0);
                }
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!hand.getType().isAir() && hand.hasItemMeta()) {
                triggerItemEnchants(hand, TriggerType.PASSIVE, player, null, player.getLocation(), 0);
            }
        }
    }

    private void triggerItemEnchants(ItemStack item, TriggerType trigger, Player self, LivingEntity target, Location impactLoc, double damageDealt) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (CustomEnchant enchant : enchants.values()) {
            if (enchant.getTriggerType() != trigger) continue;
            if (!pdc.has(getAppliedKey(enchant.getId()), PersistentDataType.BYTE)) continue;

            if (ThreadLocalRandom.current().nextDouble() > enchant.getChance()) continue;
            if (isOnCooldown(self, enchant)) continue;

            executeActions(enchant.getActions(), self, target, impactLoc, damageDealt);
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

    private void executeActions(List<String> actions, Player self, LivingEntity target, Location impactLoc, double damageDealt) {
        Location loc = (impactLoc != null) ? impactLoc : (target != null ? target.getLocation() : self.getLocation());

        for (String raw : actions) {
            String[] parts = raw.split(":");
            String type = parts[0].toUpperCase();

            try {
                switch (type) {
                    case "HEAL" -> healPlayer(self, Double.parseDouble(parts[1]));
                    case "LIFESTEAL" -> healPlayer(self, damageDealt * Double.parseDouble(parts[1]));
                    case "DAMAGE" -> {
                        if (target != null) target.damage(Double.parseDouble(parts[1]));
                    }
                    case "CLEAVE" -> {
                        double radius = Double.parseDouble(parts[1]);
                        double dmg = Double.parseDouble(parts[2]);
                        for (Entity nearby : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
                            if (nearby instanceof LivingEntity le && le != self && le != target) {
                                le.damage(dmg, self);
                            }
                        }
                    }
                    case "LIGHTNING" -> loc.getWorld().strikeLightning(loc);
                    case "LIGHTNING_EFFECT" -> loc.getWorld().strikeLightningEffect(loc);
                    case "IGNITE" -> {
                        if (target != null) target.setFireTicks(Integer.parseInt(parts[1]));
                    }
                    case "FREEZE" -> {
                        if (target != null) target.setFreezeTicks(Integer.parseInt(parts[1]));
                    }
                    case "EXPLODE" -> {
                        float power = Float.parseFloat(parts[1]);
                        loc.getWorld().createExplosion(loc, power, false, false);
                    }
                    case "ARROW_RAIN" -> {
                        int count = Integer.parseInt(parts[1]);
                        for (int i = 0; i < count; i++) {
                            double ox = ThreadLocalRandom.current().nextDouble(-2.5, 2.5);
                            double oz = ThreadLocalRandom.current().nextDouble(-2.5, 2.5);
                            Location spawnLoc = loc.clone().add(ox, 8.0 + (i * 0.5), oz);
                            Arrow arrow = loc.getWorld().spawnArrow(spawnLoc, new Vector(0, -1.5, 0), 1.8f, 4.0f);
                            arrow.setShooter(self);
                        }
                    }
                    case "POTION_SELF" -> applyPotion(self, parts);
                    case "POTION_TARGET" -> {
                        if (target != null) applyPotion(target, parts);
                    }
                    case "LAUNCH" -> {
                        if (target != null) target.setVelocity(target.getVelocity().setY(Double.parseDouble(parts[1])));
                    }
                    case "PULL" -> {
                        if (target != null) {
                            Vector pull = self.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(Double.parseDouble(parts[1]));
                            target.setVelocity(pull.setY(0.3));
                        }
                    }
                    case "DASH" -> {
                        double power = Double.parseDouble(parts[1]);
                        Vector dir = self.getLocation().getDirection().normalize().multiply(power);
                        self.setVelocity(dir.setY(0.35));
                    }
                    case "LEAP" -> {
                        double fwd = Double.parseDouble(parts[1]);
                        double up = Double.parseDouble(parts[2]);
                        Vector dir = self.getLocation().getDirection().setY(0).normalize().multiply(fwd);
                        self.setVelocity(dir.setY(up));
                    }
                    case "TELEPORT_HIT" -> {
                        Location tp = loc.clone();
                        tp.setYaw(self.getLocation().getYaw());
                        tp.setPitch(self.getLocation().getPitch());
                        self.teleport(tp);
                    }
                    case "FIREBALL" -> {
                        Fireball fb = self.launchProjectile(Fireball.class);
                        fb.setYield(1.5f);
                        fb.setIsIncendiary(false);
                    }
                    case "SOUND" -> {
                        Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(parts[1].toLowerCase()));
                        if (sound == null) sound = Sound.valueOf(parts[1].toUpperCase());
                        self.playSound(self.getLocation(), sound, 1.0f, 1.0f);
                    }
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

    private void healPlayer(Player player, double amount) {
        if (amount <= 0) return;
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double max = (attr != null) ? attr.getValue() : 20.0;
        player.setHealth(Math.min(max, player.getHealth() + amount));
    }

    private void applyPotion(LivingEntity entity, String[] parts) {
        PotionEffectType effectType = Registry.EFFECT.get(NamespacedKey.minecraft(parts[1].toLowerCase()));
        if (effectType != null) {
            int level = Math.max(0, Integer.parseInt(parts[2]) - 1);
            int ticks = Integer.parseInt(parts[3]);
            entity.addPotionEffect(new PotionEffect(effectType, ticks, level, true, false));
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
}
