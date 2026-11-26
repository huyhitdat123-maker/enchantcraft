package com.enchantcraft.enchants;

import org.bukkit.Material;

import java.util.List;

public class CustomEnchant {

    public enum TargetType {
        SWORD("Kiếm"),
        AXE("Rìu"),
        TRIDENT("Đinh Ba"),
        WEAPON("Vũ khí (Kiếm, Rìu, Chùy, Đinh Ba)"),
        AXE_MACE("Rìu / Chùy Mace"),
        WEAPON_BOW("Vũ khí / Cung / Nỏ"),
        WEAPON_TOOL("Vũ khí / Công cụ"),
        SWORD_PICKAXE("Kiếm / Cuốc"),
        BOW("Cung / Nỏ"),
        PICKAXE("Cuốc"),
        SHOVEL("Xẻng"),
        HOE("Cuốc Chim"),
        PICKAXE_SHOVEL("Cuốc / Xẻng"),
        TOOL("Công cụ (Cuốc, Rìu, Xẻng, Cuốc Chim)"),
        HELMET("Mũ"),
        CHESTPLATE("Áo Giáp"),
        LEGGINGS("Quần"),
        BOOTS("Giày"),
        LEGGINGS_BOOTS("Quần / Giày"),
        ARMOR("Tất cả Giáp"),
        ALL("Mọi trang bị");

        private final String label;

        TargetType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public boolean matches(Material mat) {
            String name = mat.name();
            boolean isSword = name.endsWith("_SWORD");
            boolean isAxe = name.endsWith("_AXE");
            boolean isPickaxe = name.endsWith("_PICKAXE");
            boolean isShovel = name.endsWith("_SHOVEL");
            boolean isHoe = name.endsWith("_HOE");
            boolean isWeapon = isSword || isAxe || mat == Material.MACE || mat == Material.TRIDENT;
            boolean isBow = mat == Material.BOW || mat == Material.CROSSBOW;
            boolean isTool = isPickaxe || isAxe || isShovel || isHoe;
            boolean isHelmet = name.endsWith("_HELMET") || mat == Material.TURTLE_HELMET;
            boolean isChestplate = name.endsWith("_CHESTPLATE") || mat == Material.ELYTRA;
            boolean isLeggings = name.endsWith("_LEGGINGS");
            boolean isBoots = name.endsWith("_BOOTS");
            boolean isArmor = isHelmet || isChestplate || isLeggings || isBoots;

            return switch (this) {
                case SWORD -> isSword;
                case AXE -> isAxe;
                case TRIDENT -> mat == Material.TRIDENT;
                case WEAPON -> isWeapon;
                case AXE_MACE -> isAxe || mat == Material.MACE;
                case WEAPON_BOW -> isWeapon || isBow;
                case WEAPON_TOOL -> isWeapon || isTool;
                case SWORD_PICKAXE -> isSword || isPickaxe;
                case BOW -> isBow;
                case PICKAXE -> isPickaxe;
                case SHOVEL -> isShovel;
                case HOE -> isHoe;
                case PICKAXE_SHOVEL -> isPickaxe || isShovel;
                case TOOL -> isTool;
                case HELMET -> isHelmet;
                case CHESTPLATE -> isChestplate;
                case LEGGINGS -> isLeggings;
                case BOOTS -> isBoots;
                case LEGGINGS_BOOTS -> isLeggings || isBoots;
                case ARMOR -> isArmor;
                case ALL -> true;
            };
        }
    }

    public enum TriggerType {
        ATTACK,
        DEFEND,
        MINE,
        KILL,
        SHOOT,
        INTERACT,
        SNEAK,
        SNEAK_INTERACT,
        FATAL_DAMAGE,
        PASSIVE
    }

    private final String id;
    private final String displayName;
    private final TargetType targetType;
    private final String description;
    private final TriggerType triggerType;
    private final double chance;
    private final int cooldownSeconds;
    private final List<String> actions;

    public CustomEnchant(
            String id,
            String displayName,
            TargetType targetType,
            String description,
            TriggerType triggerType,
            double chance,
            int cooldownSeconds,
            List<String> actions
    ) {
        this.id = id.toLowerCase();
        this.displayName = displayName;
        this.targetType = targetType;
        this.description = description;
        this.triggerType = triggerType;
        this.chance = chance;
        this.cooldownSeconds = cooldownSeconds;
        this.actions = actions;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public TargetType getTargetType() { return targetType; }
    public String getDescription() { return description; }
    public TriggerType getTriggerType() { return triggerType; }
    public double getChance() { return chance; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public List<String> getActions() { return actions; }
}
