package com.enchantcraft;

import com.enchantcraft.enchants.CustomEnchant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EnchantCraftPlugin extends JavaPlugin implements TabCompleter {

    private EnchantManager enchantManager;

    @Override
    public void onEnable() {
        saveDefaultEnchantsFile();
        this.enchantManager = new EnchantManager(this);
        this.enchantManager.loadEnchants();

        getServer().getPluginManager().registerEvents(enchantManager, this);
        // Nhịp quét bị động mỗi 2 giây (40 ticks) để tối ưu TPS
        Bukkit.getScheduler().runTaskTimer(this, () -> enchantManager.tickPassives(), 40L, 40L);

        if (getCommand("enchantcraft") != null) {
            getCommand("enchantcraft").setTabCompleter(this);
        }
        if (getCommand("random") != null) {
            getCommand("random").setTabCompleter(this);
        }

        getLogger().info("EnchantCraft da khoi dong thanh cong voi " + enchantManager.getAllEnchants().size() + " phu phep!");
    }

    public void saveDefaultEnchantsFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveResource("enchants.yml", false);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("random")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Lenh nay chi danh cho nguoi choi!");
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("ce")) {
                enchantManager.openRandomGui(player);
            } else {
                player.sendMessage(Component.text("Sử dụng: /random ce để mở Kho Báu Phù Phép!", NamedTextColor.YELLOW));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            saveDefaultEnchantsFile();
            enchantManager.loadEnchants();
            sender.sendMessage(Component.text("Đã tải lại EnchantCraft (" + enchantManager.getAllEnchants().size() + " phù phép)!", NamedTextColor.GREEN));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Lenh nay chi danh cho nguoi choi!");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Tổng cộng " + enchantManager.getAllEnchants().size() + " phù phép. Gõ /ec <tên> hoặc /ec reload.", NamedTextColor.YELLOW));
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
        if (command.getName().equalsIgnoreCase("random")) {
            if (args.length == 1 && "ce".startsWith(args[0].toLowerCase())) {
                return List.of("ce");
            }
            return List.of();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            suggestions.add("reload");
            enchantManager.getAllEnchants().forEach(e -> suggestions.add(e.getId()));
            return suggestions.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
