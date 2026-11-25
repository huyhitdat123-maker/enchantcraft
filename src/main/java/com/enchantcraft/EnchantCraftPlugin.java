package com.enchantcraft;

import com.enchantcraft.enchants.CustomEnchant;
import com.enchantcraft.enchants.EnchantList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

public class EnchantCraftPlugin extends JavaPlugin implements TabCompleter {

    private EnchantManager enchantManager;

    @Override
    public void onEnable() {
        this.enchantManager = new EnchantManager(this);

        // Nạp toàn bộ phù phép từ file EnchantList
        EnchantList.registerAll(enchantManager);

        getServer().getPluginManager().registerEvents(enchantManager, this);
        if (getCommand("enchantcraft") != null) {
            getCommand("enchantcraft").setTabCompleter(this);
        }

        getLogger().info("EnchantCraft da khoi dong thanh cong voi " + enchantManager.getAllEnchants().size() + " phu phep!");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Lenh nay chi danh cho nguoi choi!");
            return true;
        }

        if (args.length == 0) {
            String list = enchantManager.getAllEnchants().stream()
                    .map(CustomEnchant::getId)
                    .collect(Collectors.joining(", "));
            player.sendMessage(Component.text("Danh sách phù phép: " + list, NamedTextColor.YELLOW));
            return true;
        }

        CustomEnchant enchant = enchantManager.getEnchant(args[0]);
        if (enchant != null) {
            player.getInventory().addItem(enchantManager.createEnchantBook(enchant.getId()));
            player.sendMessage(Component.text("Đã nhận Sách Phù Phép: " + enchant.getDisplayName(), NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Không tìm thấy phù phép: " + args[0], NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return enchantManager.getAllEnchants().stream()
                    .map(CustomEnchant::getId)
                    .filter(id -> id.startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
