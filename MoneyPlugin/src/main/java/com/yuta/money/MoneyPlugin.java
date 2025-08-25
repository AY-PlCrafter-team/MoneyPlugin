package com.yuta.money;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.File;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;

public class MoneyPlugin extends JavaPlugin implements Listener {

    private static final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, Inventory> sellInventories = new HashMap<>();
    private final Map<UUID, Inventory> shopInventories = new HashMap<>();
    private final Map<UUID, String> shopGenreSelections = new HashMap<>();
    private final Map<Material, Double> sellPrices = new HashMap<>();
    private final Map<UUID, Long> setMoneyCooldowns = new HashMap<>();
    private Inventory pocket; // shared chest

    private String moneyUnit = "KP";
    private File pricesFile;
    private FileConfiguration pricesConfig;

    private File shopFile;
    private FileConfiguration shopConfig;

    @Override
    public void onEnable() {
        getLogger().info("MoneyPlugin Enabled!");
        Bukkit.getPluginManager().registerEvents(this, this);

        loadMoneyUnitFromPluginYml();
        setupPrices();
        setupShop();

        registerCommands();

        pocket = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA + "Pocket");

        new BukkitRunnable() {
            @Override
            public void run() {
                updateScoreboardsAndActionBar();
            }
        }.runTaskTimer(this, 3L, 3L);
    }

    private void loadMoneyUnitFromPluginYml() {
        try (InputStream in = getResource("plugin.yml")) {
            if (in != null) {
                YamlConfiguration yml = YamlConfiguration
                        .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                String mu = yml.getString("money-unit");
                if (mu != null && !mu.isEmpty()) {
                    moneyUnit = mu;
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to read money-unit from plugin.yml, using default 'KP'.");
        }
    }

    private void setupPrices() {
        pricesFile = new File(getDataFolder(), "prices.yml");
        if (!pricesFile.exists()) {
            getDataFolder().mkdirs();
            saveResource("prices.yml", false);
        }
        pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);
        reloadPricesToMap();
    }

    private void reloadPricesToMap() {
        sellPrices.clear();
        if (pricesConfig.contains("prices")) {
            for (String key : pricesConfig.getConfigurationSection("prices").getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null) {
                    double price = pricesConfig.getDouble("prices." + key, 0.0);
                    sellPrices.put(mat, price);
                } else {
                    getLogger().warning("Unknown material in prices.yml: " + key);
                }
            }
        }
    }

    private void setupShop() {
        shopFile = new File(getDataFolder(), "shop.yml");
        if (!shopFile.exists()) {
            getDataFolder().mkdirs();
            saveResource("shop.yml", false);
        }
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
    }

    private void registerCommands() {
        // onCommand で処理
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "money":
                if (sender instanceof Player pl) {
                    pl.sendMessage(ChatColor.GOLD + "あなたの所持金: " + ChatColor.GREEN + formatAmount(getBalance(pl)) + " "
                            + moneyUnit);
                }
                return true;

            case "money_all":
                if (!sender.isOp()) {
                    sender.sendMessage(ChatColor.RED + "このコマンドはOP専用です！");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "--- 全員の所持金 ---");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    sender.sendMessage(p.getName() + ": " + formatAmount(getBalance(p)) + " " + moneyUnit);
                }
                return true;

            case "pay":
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
                    player.sendMessage(ChatColor.GREEN + target.getName() + " に " + formatAmount(amount) + " "
                            + moneyUnit + " 渡しました。");
                    target.sendMessage(ChatColor.GREEN + player.getName() + " から " + formatAmount(amount) + " "
                            + moneyUnit + " 受け取りました。");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "金額は数値で入力してください。");
                }
                return true;

            case "want":
                if (!(sender instanceof Player player2))
                    return true;
                if (args.length != 2) {
                    player2.sendMessage(ChatColor.RED + "/want [Player] [Amount]");
                    return true;
                }
                Player target2 = Bukkit.getPlayer(args[0]);
                if (target2 == null) {
                    player2.sendMessage(ChatColor.RED + "そのプレイヤーは見つかりません。");
                    return true;
                }
                String amount = args[1];
                target2.sendMessage(
                        ChatColor.LIGHT_PURPLE + player2.getName() + " が " + amount + " " + moneyUnit + " 欲しがっています！");
                player2.sendMessage(ChatColor.YELLOW + target2.getName() + " にリクエストを送りました。");
                return true;

            case "sell":
                if (!(sender instanceof Player splayer))
                    return true;
                Inventory inv = Bukkit.createInventory(splayer, 27, ChatColor.GREEN + "売却GUI");
                sellInventories.put(splayer.getUniqueId(), inv);
                splayer.openInventory(inv);
                return true;

            case "shop":
                if (!(sender instanceof Player shopP))
                    return true;

                // ジャンル選択画面を作る
                if (!shopConfig.contains("shop")) {
                    shopP.sendMessage(ChatColor.RED + "shop.yml にショップデータがありません！");
                    return true;
                }

                Inventory genreInv = Bukkit.createInventory(shopP, 9, ChatColor.BLUE + "ショップ - ジャンル");

                for (String genre : shopConfig.getConfigurationSection("shop").getKeys(false)) {
                    String iconName = shopConfig.getString("shop." + genre + ".icon", "STONE");
                    Material iconMat = Material.matchMaterial(iconName);
                    if (iconMat == null)
                        continue;

                    ItemStack item = new ItemStack(iconMat);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.GOLD + genre);
                        item.setItemMeta(meta);
                    }
                    genreInv.addItem(item);
                }

                shopInventories.put(shopP.getUniqueId(), genreInv);
                shopP.openInventory(genreInv);
                return true;

            case "setmoney":
                if (!(sender instanceof Player op))
                    return true;
                if (!op.isOp()) {
                    op.sendMessage(ChatColor.RED + "このコマンドはOP専用です！");
                    return true;
                }
                if (args.length != 2) {
                    op.sendMessage(ChatColor.RED + "/setmoney [Player] [Amount]");
                    return true;
                }
                long now = System.currentTimeMillis();
                if (setMoneyCooldowns.containsKey(op.getUniqueId())) {
                    long last = setMoneyCooldowns.get(op.getUniqueId());
                    if (now - last < 60_000) {
                        op.sendMessage(
                                ChatColor.RED + "setmoneyは1分に1回までです！あと " + ((60_000 - (now - last)) / 1000) + "秒");
                        return true;
                    }
                }
                Player t = Bukkit.getPlayer(args[0]);
                if (t == null) {
                    op.sendMessage(ChatColor.RED + "そのプレイヤーは見つかりません。");
                    return true;
                }
                try {
                    double am = Double.parseDouble(args[1]);
                    setBalance(t, am);
                    op.sendMessage(ChatColor.GREEN + t.getName() + " の所持金を " + formatAmount(am) + " " + moneyUnit
                            + " に設定しました。");
                    t.sendMessage(ChatColor.YELLOW + "あなたの所持金が " + formatAmount(am) + " " + moneyUnit + " に設定されました！");
                    setMoneyCooldowns.put(op.getUniqueId(), now);
                } catch (NumberFormatException e) {
                    op.sendMessage(ChatColor.RED + "金額は数値で入力してください。");
                }
                return true;

            case "pocket":
                if (sender instanceof Player pp) {
                    pp.openInventory(pocket);
                }
                return true;

            case "setprices":
                if (!sender.isOp()) {
                    sender.sendMessage(ChatColor.RED + "このコマンドはOP専用です！");
                    return true;
                }
                if (args.length != 2) {
                    sender.sendMessage(ChatColor.RED + "/setprices <MATERIAL_ID> <price>");
                    return true;
                }
                Material mat = Material.matchMaterial(args[0]);
                if (mat == null) {
                    sender.sendMessage(ChatColor.RED + "不明なMATERIAL: " + args[0]);
                    return true;
                }
                try {
                    double price = Double.parseDouble(args[1]);
                    pricesConfig.set("prices." + mat.name(), price);
                    pricesConfig.save(pricesFile);
                    reloadPricesToMap();
                    sender.sendMessage(ChatColor.GREEN + mat.name() + " の売値を " + price + " に設定しました。");
                } catch (Exception ex) {
                    sender.sendMessage(ChatColor.RED + "保存に失敗: " + ex.getMessage());
                }
                return true;
        }
        return false;
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
                TextComponent mainMsg = new TextComponent(
                        ChatColor.GREEN + "売却完了！ +" + formatAmount(total) + " " + moneyUnit + " ");
                TextComponent detailsMsg = new TextComponent(
                        ChatColor.YELLOW + "" + ChatColor.BOLD + "" + ChatColor.UNDERLINE + "詳細はこちら!");

                StringBuilder details = new StringBuilder();
                for (ItemStack item : inv.getContents()) {
                    if (item != null && sellPrices.containsKey(item.getType())) {
                        details.append(item.getType())
                                .append(" x").append(item.getAmount())
                                .append(" = ").append(formatAmount(sellPrices.get(item.getType()) * item.getAmount()))
                                .append(" ").append(moneyUnit).append("\n");
                    }
                }
                detailsMsg.setHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(details.toString()).create()));
                mainMsg.addExtra(detailsMsg);
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
            if (clicked == null)
                return;

            ItemMeta meta = clicked.getItemMeta();
            if (meta == null || !meta.hasDisplayName())
                return;

            String genreSelected = shopGenreSelections.get(player.getUniqueId());
            String clickedName = ChatColor.stripColor(meta.getDisplayName());

            // --- 上部ジャンルボタンの処理 ---
            if (clickedName.equals("武器") || clickedName.equals("防具") ||
                    clickedName.equals("回復") || clickedName.equals("資材")) {
                openShopGenre(player, clickedName);
                return;
            }

            // --- 商品購入処理 ---
            if (genreSelected != null) {
                if (!shopConfig.contains("shop." + genreSelected + ".items"))
                    return;

                for (String key : shopConfig.getConfigurationSection("shop." + genreSelected + ".items")
                        .getKeys(false)) {
                    String display = shopConfig.getString("shop." + genreSelected + ".items." + key + ".display", key);
                    double price = shopConfig.getDouble("shop." + genreSelected + ".items." + key + ".price", 0);
                    int amount = shopConfig.getInt("shop." + genreSelected + ".items." + key + ".amount", 1);
                    List<String> genres = shopConfig
                            .getStringList("shop." + genreSelected + ".items." + key + ".genre");

                    if (!genres.contains(genreSelected))
                        continue;

                    if (clickedName.contains(display)) {
                        if (getBalance(player) >= price) {
                            withdraw(player, price);
                            Material mat = Material.matchMaterial(key);
                            if (mat != null) {
                                player.getInventory().addItem(new ItemStack(mat, amount));
                                player.sendMessage(ChatColor.GREEN + display + " を購入しました！(-" +
                                        formatAmount(price) + moneyUnit + ")");
                            } else {
                                player.sendMessage(ChatColor.RED + "アイテムが見つかりません: " + key);
                            }
                        } else {
                            player.sendMessage(ChatColor.RED + "お金が足りません！");
                        }
                        break;
                    }
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

    private void updateScoreboardsAndActionBar() {
        List<Player> sortedPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        sortedPlayers.sort((a, b) -> Double.compare(getBalance(b), getBalance(a)));

        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = sb.registerNewObjective("ranking", "dummy", ChatColor.GOLD.toString() + "KPランキング");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            int count = 1;
            boolean isListed = false;
            for (Player target : sortedPlayers) {
                double bal = getBalance(target);
                String color = target.equals(p) ? ChatColor.YELLOW.toString() : ChatColor.GREEN.toString();
                String entry = color + count + "位 " + target.getName() + " " + formatAmount(bal) + moneyUnit;
                obj.getScore(entry).setScore(10 - count);
                if (target.equals(p))
                    isListed = true;
                count++;
                if (count > 10)
                    break;
            }

            if (!isListed) {
                int selfRank = sortedPlayers.indexOf(p) + 1;
                double selfBal = getBalance(p);
                obj.getScore(ChatColor.YELLOW.toString() + "~~~~~~~~~").setScore(0);
                obj.getScore(ChatColor.YELLOW.toString() + selfRank + "位 " + p.getName() + " " + formatAmount(selfBal)
                        + moneyUnit).setScore(-1);
            }

            p.setScoreboard(sb);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)
                continue;

            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;

            for (Player other : p.getWorld().getPlayers()) {
                if (other.equals(p))
                    continue;
                if (other.getGameMode() != GameMode.SURVIVAL && other.getGameMode() != GameMode.ADVENTURE)
                    continue;
                double dist = p.getLocation().distance(other.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = other;
                }
            }

            String loc = "(" +
                    p.getLocation().getBlockX() + ", " +
                    p.getLocation().getBlockY() + ", " +
                    p.getLocation().getBlockZ() + ")";

            String msg;
            if (nearest != null) {
                msg = ChatColor.AQUA + "location: " + loc +
                        ChatColor.YELLOW + "  @p: " + nearest.getName() +
                        ChatColor.GRAY + "  distance: " + new DecimalFormat("#0.0").format(nearestDist);
            } else {
                msg = ChatColor.AQUA + "location: " + loc +
                        ChatColor.YELLOW + "  @p: -" +
                        ChatColor.GRAY + "  distance: -";
            }

            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        }
    }

    private String formatAmount(double n) {
        final String[] units = { "", "K", "M", "B", "T", "Q", "Qi", "Sx", "Sp" };
        int idx = 0;
        while (Math.abs(n) >= 1000.0 && idx < units.length - 1) {
            n /= 1000.0;
            idx++;
        }
        String s = (Math.abs(n) >= 100 ? new DecimalFormat("#0").format(n)
                : Math.abs(n) >= 10 ? new DecimalFormat("#0.0").format(n)
                        : new DecimalFormat("#0.0").format(n));
        return s + units[idx];
    }

    private void openShopGenre(Player player, String genre) {
        Inventory inv = Bukkit.createInventory(player, 54, ChatColor.BLUE + "ショップ - " + genre);

        // 1列目: ジャンルボタン
        int slotIndex = 0;
        for (String g : shopConfig.getConfigurationSection("shop").getKeys(false)) {
            String iconName = shopConfig.getString("shop." + g + ".icon", "STONE");
            Material iconMat = Material.matchMaterial(iconName);
            if (iconMat == null)
                continue;

            ItemStack button = new ItemStack(iconMat);
            ItemMeta meta = button.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + g);
                button.setItemMeta(meta);
            }
            inv.setItem(slotIndex, button);
            slotIndex++;
        }

        // 2列目: 区切り用ガラス
        for (int i = 9; i < 18; i++) {
            inv.setItem(i, new ItemStack(Material.GLASS_PANE));
        }

        // 3列目以降: 商品
        if (shopConfig.contains("shop." + genre + ".items")) {
            int itemSlot = 18;
            for (String key : shopConfig.getConfigurationSection("shop." + genre + ".items").getKeys(false)) {
                String display = shopConfig.getString("shop." + genre + ".items." + key + ".display", key);
                int amount = shopConfig.getInt("shop." + genre + ".items." + key + ".amount", 1);
                double price = shopConfig.getDouble("shop." + genre + ".items." + key + ".price", 0);
                List<String> genres = shopConfig.getStringList("shop." + genre + ".items." + key + ".genre");
                if (!genres.contains(genre))
                    continue;

                Material mat = Material.matchMaterial(key);
                if (mat == null)
                    continue;

                ItemStack item = new ItemStack(mat, amount);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.AQUA + display + ChatColor.GRAY + " (" + price + moneyUnit + ")");
                    item.setItemMeta(meta);
                }
                inv.setItem(itemSlot, item);
                itemSlot++;
            }
        }

        shopInventories.put(player.getUniqueId(), inv);
        shopGenreSelections.put(player.getUniqueId(), genre);
        player.openInventory(inv);
    }

    @Override
    public void onDisable() {
        getLogger().info("MoneyPlugin Disabled!");
    }
}