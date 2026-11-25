package com.enchantcraft.enchants;

import com.enchantcraft.EnchantManager;
import com.enchantcraft.enchants.CustomEnchant.TargetType;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

import java.util.concurrent.ThreadLocalRandom;

public class EnchantList {

    public static void registerAll(EnchantManager manager) {

        // 1. Phù phép: Hút Máu I
        manager.register(new CustomEnchant(
                "hut_mau",
                "Hút Máu I",
                TargetType.SWORD,
                "30% tỉ lệ hồi 2 máu khi chém trúng mục tiêu"
        ).setRecipe(
                "RDR",
                "DBD",
                "RDR",
                'R', Material.REDSTONE_BLOCK,
                'D', Material.DIAMOND,
                'B', Material.BOOK
        ).onAttack((attacker, target, event) -> {
            if (ThreadLocalRandom.current().nextDouble() <= 0.30) {
                AttributeInstance maxHealthAttr = attacker.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = (maxHealthAttr != null) ? maxHealthAttr.getValue() : 20.0;
                attacker.setHealth(Math.min(maxHealth, attacker.getHealth() + 2.0));
            }
        }));

        // ==========================================================
        // DÁN CODE ENCHANT MỚI VÀO NGAY BÊN DƯỚI DÒNG NÀY LÀ XONG!
        // ==========================================================

    }
}
