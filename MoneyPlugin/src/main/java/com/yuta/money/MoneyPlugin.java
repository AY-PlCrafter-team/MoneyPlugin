package com.yuta.money;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.util.*;

public class MoneyPlugin extends JavaPlugin implements Listener {

    private static final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, Inventory> sellInventories = new HashMap<>();
    private final Map<UUID, Inventory> shopInventories = new HashMap<>();
    private final Map<Material, Double> sellPrices = new HashMap<>();
    private final Map<UUID, Long> setMoneyCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("MoneyPlugin Enabled!");
        Bukkit.getPluginManager().registerEvents(this, this);

        // 売却価格設定
        sellPrices.put(Material.COBBLESTONE, 0.1);
        sellPrices.put(Material.STONE, 0.1);
        sellPrices.put(Material.COAL, 0.2);
        sellPrices.put(Material.COAL_BLOCK, 2.3);
        sellPrices.put(Material.COPPER_ORE, 1.0);
        sellPrices.put(Material.COPPER_INGOT, 1.2);
        sellPrices.put(Material.RAW_COPPER_BLOCK, 14.0);
        sellPrices.put(Material.GOLD_ORE, 1.2);
        sellPrices.put(Material.GOLD_INGOT, 1.5);
        sellPrices.put(Material.RAW_GOLD_BLOCK, 14.0);
        sellPrices.put(Material.IRON_ORE, 3.4);
        sellPrices.put(Material.IRON_INGOT, 4.2);
        sellPrices.put(Material.RAW_IRON_BLOCK, 49.0);
        sellPrices.put(Material.EMERALD, 1.3);
        sellPrices.put(Material.EMERALD_BLOCK, 15.0);
        sellPrices.put(Material.LAPIS_LAZULI, 1.3);
        sellPrices.put(Material.LAPIS_BLOCK, 15.0);
        sellPrices.put(Material.DIAMOND, 9.0);
        sellPrices.put(Material.DIAMOND_BLOCK, 105.0);
        sellPrices.put(Material.QUARTZ, 2.5);
        sellPrices.put(Material.QUARTZ_BLOCK, 29.3);
        sellPrices.put(Material.ANCIENT_DEBRIS, 10.0);
        sellPrices.put(Material.NETHERITE_SCRAP, 12.0);
        sellPrices.put(Material.NETHERITE_INGOT, 55.0);

        registerCommands();

        // 常時更新タスク
        new BukkitRunnable() {
            @Override
            public void run() {
                updateScoreboardsAndActionBar();
            }
        }.runTaskTimer(this, 20L, 40L);
    }

    private void registerCommands() {
        // /money
        Objects.requireNonNull(getCommand("money")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player))
                return true;
            player.sendMessage(
                    ChatColor.GOLD + "あなたの所持金: " + ChatColor.GREEN + String.format("%.1f", getBalance(player)) + " KP");
            return true;
        });

        // /money_all
        Objects.requireNonNull(getCommand("money_all")).setExecutor((sender, cmd, label, args) -> {
            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "このコマンドはOP専用です！");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "--- 全員の所持金 ---");
            for (Player p : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(p.getName() + ": " + String.format("%.1f", getBalance(p)) + " KP");
            }
            return true;
        });

        // /pay
        Objects.requireNonNull(getCommand("pay")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player))
                return true;
            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "/pay [Player] [Amount]");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "そのプレイヤーは見つかりません。");
                return true;
            }
            try {
                double amount = Double.parseDouble(args[1]);
                if (getBalance(player) < amount) {
                    player.sendMessage(ChatColor.RED + "お金が足りません！");
                    return true;
                }
                withdraw(player, amount);
                deposit(target, amount);
                player.sendMessage(ChatColor.GREEN + target.getName() + " に " + amount + " KP 渡しました。");
                target.sendMessage(ChatColor.GREEN + player.getName() + " から " + amount + " KP 受け取りました。");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "金額は数値で入力してください。");
            }
            return true;
        });

        // /want
        Objects.requireNonNull(getCommand("want")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player))
                return true;
            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "/want [Player] [Amount]");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "そのプレイヤーは見つかりません。");
                return true;
            }
            String amount = args[1];
            target.sendMessage(ChatColor.LIGHT_PURPLE + player.getName() + " が " + amount + " KP 欲しがっています！");
            player.sendMessage(ChatColor.YELLOW + target.getName() + " にリクエストを送りました。");
            return true;
        });

        // /sell
        Objects.requireNonNull(getCommand("sell")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player))
                return true;
            Inventory inv = Bukkit.createInventory(player, 27, ChatColor.GREEN + "売却GUI");
            sellInventories.put(player.getUniqueId(), inv);
            player.openInventory(inv);
            return true;
        });

        // /shop
        Objects.requireNonNull(getCommand("shop")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player))
                return true;
            Inventory inv = Bukkit.createInventory(player, 9, ChatColor.BLUE + "ショップ");
            addShopItem(inv, Material.DIAMOND_PICKAXE, ChatColor.AQUA + "ダイヤモンドのツルハシ", 20, 4);
            shopInventories.put(player.getUniqueId(), inv);
            player.openInventory(inv);
            return true;
        });

        // /setmoney
        Objects.requireNonNull(getCommand("setmoney")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player))
                return true;
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "このコマンドはOP専用です！");
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "/setmoney [Player] [Amount]");
                return true;
            }
            long now = System.currentTimeMillis();
            if (setMoneyCooldowns.containsKey(player.getUniqueId())) {
                long last = setMoneyCooldowns.get(player.getUniqueId());
                if (now - last < 5_000) {
                    player.sendMessage(
                            ChatColor.RED + "setmoneyは1分に1回までです！ ※5秒に短縮中あと " + ((5_000 - (now - last)) / 1000) + "秒");
                    return true;
                }
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "そのプレイヤーは見つかりません。");
                return true;
            }
            try {
                double amount = Double.parseDouble(args[1]);
                setBalance(target, amount);
                player.sendMessage(ChatColor.GREEN + target.getName() + " の所持金を " + amount + " KP に設定しました。");
                target.sendMessage(ChatColor.YELLOW + "あなたの所持金が " + amount + " KP に設定されました！");
                setMoneyCooldowns.put(player.getUniqueId(), now);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "金額は数値で入力してください。");
            }
            return true;
        });
    }

    private void addShopItem(Inventory inv, Material type, String displayName, double price, int slot) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName + " (" + price + "KP)");
            item.setItemMeta(meta);
        }
        inv.setItem(slot, item);
    }

    @Override
    public void onDisable() {
        getLogger().info("MoneyPlugin Disabled!");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Player player = (Player) e.getPlayer();
        Inventory inv = sellInventories.get(player.getUniqueId());
        if (inv != null && e.getInventory().equals(inv)) {
            double total = 0;
            List<ItemStack> unsold = new ArrayList<>();
            for (ItemStack item : inv.getContents()) {
                if (item != null) {
                    if (sellPrices.containsKey(item.getType())) {
                        total += sellPrices.get(item.getType()) * item.getAmount();
                    } else {
                        unsold.add(item);
                    }
                }
            }

            if (total > 0) {
                deposit(player, total);

                // main メッセージ
                TextComponent mainMsg = new TextComponent(
                        ChatColor.GREEN + "売却完了！ +" + String.format("%.1f", total) + " KP ");

                // 「詳細はこちら！」部分（黄色 + 太字 + 下線）
                TextComponent detailsMsg = new TextComponent(
                        ChatColor.YELLOW + "" + ChatColor.BOLD + "" + ChatColor.UNDERLINE + "詳細はこちら!");

                // Hoverで詳細表示
                StringBuilder details = new StringBuilder();
                for (ItemStack item : inv.getContents()) {
                    if (item != null && sellPrices.containsKey(item.getType())) {
                        details.append(item.getType())
                                .append(" x").append(item.getAmount())
                                .append(" = ")
                                .append(String.format("%.1f", sellPrices.get(item.getType()) * item.getAmount()))
                                .append(" KP\n");
                    }
                }
                detailsMsg.setHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(details.toString()).create()));

                // mainMsgにdetailsMsgを追加
                mainMsg.addExtra(detailsMsg);

                // 送信
                player.spigot().sendMessage(mainMsg);
            } else {
                player.sendMessage(ChatColor.RED + "売れるアイテムがありませんでした。");
            }

            for (ItemStack item : unsold) {
                if (item != null)
                    player.getInventory().addItem(item);
            }
            sellInventories.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        Inventory shopInv = shopInventories.get(player.getUniqueId());
        if (shopInv != null && e.getInventory().equals(shopInv)) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && clicked.getType() == Material.DIAMOND_PICKAXE) {
                if (getBalance(player) >= 20) {
                    withdraw(player, 20);
                    player.getInventory().addItem(new ItemStack(Material.DIAMOND_PICKAXE));
                    player.sendMessage(ChatColor.GREEN + "ダイヤのツルハシを購入しました！(-20KP)");
                } else {
                    player.sendMessage(ChatColor.RED + "お金が足りません！");
                }
            }
        }
    }

    public static double getBalance(Player p) {
        return balances.getOrDefault(p.getUniqueId(), 0.0);
    }

    public static void setBalance(Player p, double amount) {
        balances.put(p.getUniqueId(), amount);
    }

    public static void deposit(Player p, double amount) {
        setBalance(p, getBalance(p) + amount);
    }

    public static void withdraw(Player p, double amount) {
        setBalance(p, Math.max(0, getBalance(p) - amount));
    }

    // 数字を短縮表記にする関数
    private String formatBalance(double value) {
        String[] suffixes = { "", "K", "M", "B", "T", "Q", "Qi", "Sx" }; // 最大8つ
        int index = 0;
        while (value >= 1000 && index < suffixes.length - 1) {
            value /= 1000.0;
            index++;
        }
        return String.format("%.1f%s", value, suffixes[index]);
    }

    private void updateScoreboardsAndActionBar() {
        List<Player> sortedPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        sortedPlayers.sort((a, b) -> Double.compare(getBalance(b), getBalance(a)));
    
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Scoreboard
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = sb.registerNewObjective("ranking", "dummy", ChatColor.GOLD.toString() + "KPランキング");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
    
            int count = 1;
            boolean isListed = false;
            for (Player target : sortedPlayers) {
                double bal = getBalance(target);
                String color = target.equals(p) ? ChatColor.YELLOW.toString() : ChatColor.GREEN.toString();
                String entry = color + count + "位 " + target.getName() + " " + formatBalance(bal) + "KP";
                obj.getScore(entry).setScore(10 - count);
                if (target.equals(p)) isListed = true;
                count++;
                if (count > 10) break;
            }
    
            if (!isListed) {
                int selfRank = sortedPlayers.indexOf(p) + 1;
                double selfBal = getBalance(p);
                obj.getScore(ChatColor.YELLOW.toString() + "~~~~~~~~~").setScore(0);
                obj.getScore(ChatColor.YELLOW.toString() + selfRank + "位 " + p.getName() + " " + formatBalance(selfBal) + "KP").setScore(-1);
            }
    
            p.setScoreboard(sb);
        }
    
        // ActionBar更新（各プレイヤーごとに最も近いプレイヤーを表示）
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) continue;
    
            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;
    
            for (Player other : p.getWorld().getPlayers()) {
                if (other.equals(p)) continue;
                double dist = p.getLocation().distance(other.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = other;
                }
            }
    
            String msg;
            if (nearest != null) {
                double bal = getBalance(nearest);
                msg = ChatColor.AQUA.toString() + nearest.getName() + " " + ChatColor.YELLOW.toString() + formatBalance(bal) + "KP";
            } else {
                double bal = getBalance(p);
                msg = ChatColor.AQUA.toString() + p.getName() + " " + ChatColor.YELLOW.toString() + formatBalance(bal) + "KP";
            }
    
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        }
    }
}