package com.enchantcraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public class EnchantCraftPlugin extends JavaPlugin {

    private EnchantManager enchantManager;

    @Override
    public void onEnable() {
        this.enchantManager = new EnchantManager(this);

        // Đăng ký sự kiện ép sách & kích hoạt hiệu ứng
        getServer().getPluginManager().registerEvents(enchantManager, this);

        // Đăng ký công thức chế tạo sách phù phép
        registerEnchantRecipes();

        getLogger().info("EnchantCraft da khoi dong thanh cong!");
    }

    private void registerEnchantRecipes() {
        // Công thức mẫu: Sách Hút Máu I
        NamespacedKey hutMauKey = new NamespacedKey(this, "craft_hut_mau_1");
        if (Bukkit.getRecipe(hutMauKey) != null) {
            Bukkit.removeRecipe(hutMauKey);
        }

        ShapedRecipe hutMauRecipe = new ShapedRecipe(hutMauKey, enchantManager.createEnchantBook("hut_mau"));
        hutMauRecipe.shape(
                "RDR",
                "DBD",
                "RDR"
        );
        hutMauRecipe.setIngredient('R', Material.REDSTONE_BLOCK);
        hutMauRecipe.setIngredient('D', Material.DIAMOND);
        hutMauRecipe.setIngredient('B', Material.BOOK);

        Bukkit.addRecipe(hutMauRecipe);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Lenh nay chi danh cho nguoi choi!");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Sử dụng: /ec hut_mau", NamedTextColor.YELLOW));
            return true;
        }

        String id = args[0].toLowerCase();
        if (id.equals("hut_mau")) {
            player.getInventory().addItem(enchantManager.createEnchantBook("hut_mau"));
            player.sendMessage(Component.text("Đã nhận Sách Phù Phép: Hút Máu I", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Không tìm thấy phù phép: " + id, NamedTextColor.RED));
        }
        return true;
    }
}
