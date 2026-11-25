package com.enchantcraft.enchants;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

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

    private final String id;
    private final String displayName;
    private final String description;
    private final TargetType targetType;

    private String[] recipeShape;
    private final Map<Character, Material> recipeIngredients = new HashMap<>();

    private TriConsumer<Player, LivingEntity, EntityDamageByEntityEvent> attackAction;
    private BiConsumer<Player, EntityDamageByEntityEvent> defendAction;
    private TriConsumer<Player, Block, BlockBreakEvent> mineAction;
    private BiConsumer<Player, PlayerInteractEvent> interactAction;

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    public CustomEnchant(String id, String displayName, TargetType targetType, String description) {
        this.id = id.toLowerCase();
        this.displayName = displayName;
        this.targetType = targetType;
        this.description = description;
    }

    public CustomEnchant setRecipe(String row1, String row2, String row3, Object... ingredients) {
        this.recipeShape = new String[]{row1, row2, row3};
        for (int i = 0; i < ingredients.length; i += 2) {
            this.recipeIngredients.put((Character) ingredients[i], (Material) ingredients[i + 1]);
        }
        return this;
    }

    public CustomEnchant onAttack(TriConsumer<Player, LivingEntity, EntityDamageByEntityEvent> action) {
        this.attackAction = action;
        return this;
    }

    public CustomEnchant onDefend(BiConsumer<Player, EntityDamageByEntityEvent> action) {
        this.defendAction = action;
        return this;
    }

    public CustomEnchant onMine(TriConsumer<Player, Block, BlockBreakEvent> action) {
        this.mineAction = action;
        return this;
    }

    public CustomEnchant onInteract(BiConsumer<Player, PlayerInteractEvent> action) {
        this.interactAction = action;
        return this;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public TargetType getTargetType() { return targetType; }
    public String[] getRecipeShape() { return recipeShape; }
    public Map<Character, Material> getRecipeIngredients() { return recipeIngredients; }

    public void triggerAttack(Player attacker, LivingEntity target, EntityDamageByEntityEvent event) {
        if (attackAction != null) attackAction.accept(attacker, target, event);
    }

    public void triggerDefend(Player victim, EntityDamageByEntityEvent event) {
        if (defendAction != null) defendAction.accept(victim, event);
    }

    public void triggerMine(Player miner, Block block, BlockBreakEvent event) {
        if (mineAction != null) mineAction.accept(miner, block, event);
    }

    public void triggerInteract(Player player, PlayerInteractEvent event) {
        if (interactAction != null) interactAction.accept(player, event);
    }
}
