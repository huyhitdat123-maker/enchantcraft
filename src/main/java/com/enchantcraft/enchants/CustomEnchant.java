package com.enchantcraft.enchants;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public class CustomEnchant {

    public enum TargetType {
        SWORD("Kiếm"),
        AXE("Rìu"),
        WEAPON("Vũ khí (Kiếm, Rìu, Chùy, Đinh Ba)"),
        BOW("Cung / Nỏ"),
        PICKAXE("Cuốc"),
        ARMOR("Giáp"),
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
            return switch (this) {
                case SWORD -> name.endsWith("_SWORD");
                case AXE -> name.endsWith("_AXE");
                case WEAPON -> name.endsWith("_SWORD") || name.endsWith("_AXE") || mat == Material.MACE || mat == Material.TRIDENT;
                case BOW -> mat == Material.BOW || mat == Material.CROSSBOW;
                case PICKAXE -> name.endsWith("_PICKAXE");
                case ARMOR -> name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
                case ALL -> true;
            };
        }
    }

    public enum TriggerType {
        ATTACK,
        DEFEND,
        MINE,
        INTERACT
    }

    private final String id;
    private final String displayName;
    private final TargetType targetType;
    private final String description;
    private final TriggerType triggerType;
    private final double chance;
    private final int cooldownSeconds;
    private final List<String> recipeShape;
    private final Map<Character, Material> recipeIngredients;
    private final List<String> actions;

    public CustomEnchant(
            String id,
            String displayName,
            TargetType targetType,
            String description,
            TriggerType triggerType,
            double chance,
            int cooldownSeconds,
            List<String> recipeShape,
            Map<Character, Material> recipeIngredients,
            List<String> actions
    ) {
        this.id = id.toLowerCase();
        this.displayName = displayName;
        this.targetType = targetType;
        this.description = description;
        this.triggerType = triggerType;
        this.chance = chance;
        this.cooldownSeconds = cooldownSeconds;
        this.recipeShape = recipeShape;
        this.recipeIngredients = recipeIngredients;
        this.actions = actions;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public TargetType getTargetType() { return targetType; }
    public String getDescription() { return description; }
    public TriggerType getTriggerType() { return triggerType; }
    public double getChance() { return chance; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public List<String> getRecipeShape() { return recipeShape; }
    public Map<Character, Material> getRecipeIngredients() { return recipeIngredients; }
    public List<String> getActions() { return actions; }
}
