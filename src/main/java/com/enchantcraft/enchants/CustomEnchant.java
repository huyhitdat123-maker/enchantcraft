package com.enchantcraft.enchants;

import org.bukkit.Material;
import java.util.List;

public class CustomEnchant {

    public enum TargetType {
        ALL("Mọi trang bị & công cụ"),
        WEAPON("Kiếm & Rìu"),
        SWORD("Kiếm"),
        AXE_MACE("Rìu & Chùy"),
        BOW("Cung"),
        WEAPON_BOW("Vũ khí & Cung"),
        TRIDENT("Đinh Ba"),
        TOOL("Công cụ (Cuốc, Rìu, Xẻng, Cúp)"),
        PICKAXE("Cúp"),
        PICKAXE_SHOVEL("Cúp & Xẻng"),
        SWORD_PICKAXE("Kiếm & Cúp"),
        WEAPON_TOOL("Vũ khí & Công cụ"),
        HOE("Cuốc"),
        ARMOR("Giáp (Mũ, Áo, Quần, Giày)"),
        HELMET("Mũ"),
        CHESTPLATE("Áo Giáp"),
        LEGGINGS("Quần"),
        BOOTS("Giày"),
        LEGGINGS_BOOTS("Quần & Giày");

        private final String label;

        TargetType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public boolean matches(Material mat) {
            String name = mat.name();
            return switch (this) {
                case ALL -> isWeapon(name) || isTool(name) || isArmor(name) || mat == Material.BOW || mat == Material.CROSSBOW || mat == Material.TRIDENT || mat == Material.SHIELD || mat == Material.MACE;
                case WEAPON -> isWeapon(name) || mat == Material.MACE;
                case SWORD -> name.endsWith("_SWORD");
                case AXE_MACE -> name.endsWith("_AXE") || mat == Material.MACE;
                case BOW -> mat == Material.BOW || mat == Material.CROSSBOW;
                case WEAPON_BOW -> isWeapon(name) || mat == Material.BOW || mat == Material.CROSSBOW || mat == Material.MACE;
                case TRIDENT -> mat == Material.TRIDENT;
                case TOOL -> isTool(name);
                case PICKAXE -> name.endsWith("_PICKAXE");
                case PICKAXE_SHOVEL -> name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL");
                case SWORD_PICKAXE -> name.endsWith("_SWORD") || name.endsWith("_PICKAXE");
                case WEAPON_TOOL -> isWeapon(name) || isTool(name) || mat == Material.MACE;
                case HOE -> name.endsWith("_HOE");
                case ARMOR -> isArmor(name);
                case HELMET -> name.endsWith("_HELMET") || mat == Material.TURTLE_HELMET;
                case CHESTPLATE -> name.endsWith("_CHESTPLATE");
                case LEGGINGS -> name.endsWith("_LEGGINGS");
                case BOOTS -> name.endsWith("_BOOTS");
                case LEGGINGS_BOOTS -> name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
            };
        }

        private boolean isWeapon(String name) {
            return name.endsWith("_SWORD") || name.endsWith("_AXE");
        }

        private boolean isTool(String name) {
            return name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE");
        }

        private boolean isArmor(String name) {
            return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
        }
    }

    public enum TriggerType {
        ATTACK,
        DEFEND,
        MINE,
        INTERACT,
        SNEAK_INTERACT,
        SNEAK,
        SHOOT,
        KILL,
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
    private final int maxLevel;
    private final boolean activeSkill;
    private final List<String> actions;

    public CustomEnchant(String id, String displayName, TargetType targetType, String description,
                         TriggerType triggerType, double chance, int cooldownSeconds,
                         int maxLevel, boolean activeSkill, List<String> actions) {
        this.id = id.toLowerCase();
        this.displayName = displayName;
        this.targetType = targetType;
        this.description = description;
        this.triggerType = triggerType;
        this.chance = chance;
        this.cooldownSeconds = cooldownSeconds;
        this.maxLevel = Math.max(1, maxLevel);
        this.activeSkill = activeSkill;
        this.actions = actions;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public String getDescription() {
        return description;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public double getChance() {
        return chance;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public boolean isActiveSkill() {
        return activeSkill;
    }

    public List<String> getActions() {
        return actions;
    }
}
