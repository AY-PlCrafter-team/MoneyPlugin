package com.yuta.money;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class MoneyPlugin extends JavaPlugin implements Listener {

    // --- データ関連 ---
    private static final Map<UUID, Double> balances = new HashMap<>();
    private static final Map<UUID, String> playerNames = new HashMap<>();
    private static final Map<UUID, Integer> playTimes = new HashMap<>();
    private static final Map<Material, PriceRange> priceRanges = new HashMap<>();
    private static final Map<Material, Double> currentPrices = new HashMap<>();
    private static final Map<Material, String> itemNames = new HashMap<>();

    // --- インベントリ・選択状態関連 ---
    private final Map<UUID, Inventory> sellInventories = new HashMap<>();
    private final Map<UUID, Inventory> shopInventories = new HashMap<>();
    private final Map<UUID, String> shopGenreSelections = new HashMap<>();
    private final Map<UUID, Long> setMoneyCooldowns = new HashMap<>();
    private Inventory pocket; // 共有チェスト

    // --- 設定ファイル関連 ---
    private File pricesFile;
    private FileConfiguration pricesConfig;
    private File shopFile;
    private FileConfiguration shopConfig;
    private File balancesFile;
    private FileConfiguration balancesConfig;
    private File itemNamesFile;
    private FileConfiguration itemNamesConfig;
    private File requestsFile;
    private FileConfiguration requestsConfig;

    // --- プラグイン設定 ---
    private String moneyUnit = "KP";
    private List<Material> scoreboardPriceMaterials = new ArrayList<>();

    // --- スコアボード関連 ---
    private enum ScoreboardType { RANKING, PRICES }
    private ScoreboardType currentScoreboardType = ScoreboardType.RANKING;

    // --- ボスバー関連 ---
    private BossBar priceUpdateBossBar;
    private long nextFluctuationTime;
    private long fluctuationInterval;

    // 価格範囲を保持するためのインナークラス
    private static class PriceRange {
        double normal;
        double min;
        double max;
    }

    @Override
    public void onEnable() {
        getLogger().info("MoneyPlugin 有効化中...");
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        loadSettingsFromConfig();
        setupPrices();
        setupShop();
        loadBalances();
        loadItemNames();
        setupRequests();

        registerCommands();

        pocket = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA + "Pocket");

        startTasks();
        getLogger().info("MoneyPlugin 有効化完了！");
    }

    @Override
    public void onDisable() {
        saveBalances();
        if (priceUpdateBossBar != null) {
            priceUpdateBossBar.removeAll();
        }
        getLogger().info("MoneyPlugin 無効化完了！");
    }

    // config.ymlから設定を読み込む
    private void loadSettingsFromConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        moneyUnit = config.getString("money-unit", "KP");

        scoreboardPriceMaterials.clear();
        List<String> materialNames = config.getStringList("scoreboard-prices");
        for (String name : materialNames) {
            Material mat = Material.matchMaterial(name);
            if (mat != null) {
                scoreboardPriceMaterials.add(mat);
            } else {
                getLogger().warning("scoreboard-pricesリストに無効なマテリアルがあります: " + name);
            }
        }
    }

    // prices.ymlのセットアップ
    private void setupPrices() {
        pricesFile = new File(getDataFolder(), "prices.yml");
        if (!pricesFile.exists()) {
            saveResource("prices.yml", false);
        }
        pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);
        loadPriceData();
    }

    // 価格データをメモリに読み込む
    private void loadPriceData() {
        priceRanges.clear();
        currentPrices.clear();
        ConfigurationSection pricesSection = pricesConfig.getConfigurationSection("prices");
        if (pricesSection != null) {
            for (String key : pricesSection.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null && pricesSection.isConfigurationSection(key)) {
                    PriceRange range = new PriceRange();
                    range.normal = pricesSection.getDouble(key + ".normal", 0.0);
                    range.min = pricesSection.getDouble(key + ".min", 0.0);
                    range.max = pricesSection.getDouble(key + ".max", 0.0);
                    priceRanges.put(mat, range);
                    currentPrices.put(mat, range.normal); // 現在価格を通常価格で初期化
                } else {
                    getLogger().warning("prices.ymlに不明なマテリアルまたは不正な形式の項目があります: " + key);
                }
            }
        }
    }

    // item-names.ymlを読み込む
    private void loadItemNames() {
        itemNamesFile = new File(getDataFolder(), "item-names.yml");
        if (!itemNamesFile.exists()) {
            saveResource("item-names.yml", false);
        }
        itemNamesConfig = YamlConfiguration.loadConfiguration(itemNamesFile);
        itemNames.clear();
        ConfigurationSection namesSection = itemNamesConfig.getConfigurationSection("item-names");
        if (namesSection != null) {
            for (String key : namesSection.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null) {
                    itemNames.put(mat, namesSection.getString(key));
                }
            }
        }
    }

    // shop.ymlのセットアップ
    private void setupShop() {
        shopFile = new File(getDataFolder(), "shop.yml");
        if (!shopFile.exists()) {
            saveResource("shop.yml", false);
        }
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
    }

    // request.ymlのセットアップ
    private void setupRequests() {
        requestsFile = new File(getDataFolder(), "request.yml");
        if (!requestsFile.exists()) {
            try {
                requestsFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("request.ymlの作成に失敗しました。");
                e.printStackTrace();
            }
        }
        requestsConfig = YamlConfiguration.loadConfiguration(requestsFile);
    }

    // コマンドを登録する
    private void registerCommands() {
        getCommand("shop").setTabCompleter(this);
        getCommand("pocket").setTabCompleter(this);
    }

    // 定期実行タスクを開始する
    private void startTasks() {
        // メインのスコアボード更新タスク (3tickごと)
        new BukkitRunnable() {
            @Override
            public void run() {
                updateScoreboard();
                updateActionBar();
            }
        }.runTaskTimer(this, 3L, 3L);

        // スコアボード表示切替タスク (30秒ごと)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (currentScoreboardType == ScoreboardType.RANKING) {
                    currentScoreboardType = ScoreboardType.PRICES;
                } else {
                    currentScoreboardType = ScoreboardType.RANKING;
                }
            }
        }.runTaskTimer(this, 600L, 600L); // 30秒 * 20tick

        // 価格変動タスクとボスバータイマー
        if (getConfig().getBoolean("price-fluctuation.enabled", false)) {
            fluctuationInterval = getConfig().getLong("price-fluctuation.interval-minutes", 60) * 60 * 20;
            nextFluctuationTime = System.currentTimeMillis() + (fluctuationInterval / 20 * 1000);
            new PriceFluctuationTask().runTaskTimer(this, fluctuationInterval, fluctuationInterval);

            if (getConfig().getBoolean("price-fluctuation.bossbar-timer.enabled", true)) {
                String title = getConfig().getString("price-fluctuation.bossbar-timer.title", "&e次の価格更新まで &f%time%");
                priceUpdateBossBar = Bukkit.createBossBar(ChatColor.translateAlternateColorCodes('&', title), BarColor.BLUE, BarStyle.SOLID);
                priceUpdateBossBar.setVisible(true);
                new BossBarUpdateTask().runTaskTimer(this, 0L, 20L); // 1秒ごとに更新
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();
        if (cmdName.equals("shop") && !getConfig().getBoolean("features.shop", true)) {
            return Collections.emptyList();
        }
        if (cmdName.equals("pocket") && !getConfig().getBoolean("features.pocket", true)) {
            return Collections.emptyList();
        }
        return null;
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
                sender.sendMessage(ChatColor.YELLOW + "--- 全員の所持金ランキング ---");

                // プレイヤーを所持金でソート
                List<Player> sortedPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
                sortedPlayers.sort((p1, p2) -> Double.compare(getBalance(p2), getBalance(p1)));

                // 順位をつけて表示
                int rank = 1;
                for (Player p : sortedPlayers) {
                    sender.sendMessage(rank + "位: " + p.getName() + " - " + formatAmount(getBalance(p)) + " " + moneyUnit);
                    rank++;
                }
                return true;

            case "pay":
                if (!(sender instanceof Player player))
                    return true;
                if (args.length != 2) {
                    player.sendMessage(ChatColor.RED + "/pay <プレイヤー名> <金額>");
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
                    player2.sendMessage(ChatColor.RED + "/want <プレイヤー名> <金額>");
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
                if (!getConfig().getBoolean("features.shop", true)) {
                    sender.sendMessage(ChatColor.RED + "ショップ機能は現在無効です。");
                    return true;
                }
                if (!(sender instanceof Player shopP))
                    return true;

                if (!shopConfig.contains("shop")) {
                    shopP.sendMessage(ChatColor.RED + "shop.yml にショップデータがありません！");
                    return true;
                }

                Inventory genreInv = Bukkit.createInventory(shopP, 9, ChatColor.BLUE + "ショップ - ジャンル");

                for (String genre : shopConfig.getConfigurationSection("shop").getKeys(false)) {
                    String iconName = shopConfig.getString("shop." + genre + ".icon", "STONE");
                    Material iconMat = Material.matchMaterial(iconName);
                    if (iconMat == null) continue;

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
                    op.sendMessage(ChatColor.RED + "/setmoney <プレイヤー名> <金額>");
                    return true;
                }
                long now = System.currentTimeMillis();
                if (setMoneyCooldowns.containsKey(op.getUniqueId())) {
                    long last = setMoneyCooldowns.get(op.getUniqueId());
                    if (now - last < 60000) {
                        op.sendMessage(
                                ChatColor.RED + "setmoneyは1分に1回までです！あと " + ((60000 - (now - last)) / 1000) + "秒");
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
                if (!getConfig().getBoolean("features.pocket", true)) {
                    sender.sendMessage(ChatColor.RED + "Pocket機能は現在無効です。");
                    return true;
                }
                if (sender instanceof Player pp) {
                    pp.openInventory(pocket);
                }
                return true;

            case "setprices":
                if (!sender.isOp()) {
                    sender.sendMessage(ChatColor.RED + "このコマンドはOP専用です！");
                    return true;
                }
                if (args.length != 5) {
                    sender.sendMessage(ChatColor.RED + "/setprices <アイテムID> <日本語名> <min> <通常価格> <max>");
                    return true;
                }
                Material mat = Material.matchMaterial(args[0].toUpperCase());
                if (mat == null) {
                    sender.sendMessage(ChatColor.RED + "不明なMATERIAL: " + args[0]);
                    return true;
                }
                String japaneseName = args[1];
                try {
                    double min = Double.parseDouble(args[2]);
                    double normal = Double.parseDouble(args[3]);
                    double max = Double.parseDouble(args[4]);

                    // prices.yml を更新
                    String path = "prices." + mat.name();
                    pricesConfig.set(path + ".normal", normal);
                    pricesConfig.set(path + ".min", min);
                    pricesConfig.set(path + ".max", max);
                    pricesConfig.save(pricesFile);

                    // item-names.yml を更新
                    itemNamesConfig.set("item-names." + mat.name(), japaneseName);
                    itemNamesConfig.save(itemNamesFile);

                    // メモリ上のマップを再読み込み
                    loadPriceData();
                    loadItemNames();

                    sender.sendMessage(ChatColor.GREEN + mat.name() + " の価格情報を更新しました。");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "価格は数値で入力してください。");
                } catch (IOException e) {
                    sender.sendMessage(ChatColor.RED + "ファイルの保存に失敗しました: " + e.getMessage());
                    e.printStackTrace();
                }
                return true;

            case "request":
                if (args.length == 0) {
                    sender.sendMessage(ChatColor.RED + "使用方法: /request <要望内容>");
                    return true;
                }

                String content = String.join(" ", args);

                String senderName;
                if (sender instanceof Player) {
                    senderName = sender.getName();
                } else if (sender instanceof ConsoleCommandSender) {
                    senderName = "CONSOLE";
                } else if (sender instanceof BlockCommandSender) {
                    BlockCommandSender blockSender = (BlockCommandSender) sender;
                    senderName = "COMMAND_BLOCK (" + blockSender.getBlock().getWorld().getName() + ", "
                            + blockSender.getBlock().getX() + ", "
                            + blockSender.getBlock().getY() + ", "
                            + blockSender.getBlock().getZ() + ")";
                } else {
                    senderName = "Unknown";
                }

                Date now_req = new Date();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String timestamp = sdf.format(now_req);

                Map<String, Object> newRequest = new HashMap<>();
                newRequest.put("sender", senderName);
                newRequest.put("timestamp", timestamp);
                newRequest.put("content", content);

                List<Map<?, ?>> requests = requestsConfig.getMapList("requests");
                requests.add(newRequest);
                requestsConfig.set("requests", requests);

                try {
                    requestsConfig.save(requestsFile);
                    sender.sendMessage(ChatColor.GREEN + "要望を送信しました。ありがとうございました！");
                } catch (IOException e) {
                    sender.sendMessage(ChatColor.RED + "エラー: 要望の保存に失敗しました。");
                    e.printStackTrace();
                }
                return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (priceUpdateBossBar != null) {
            priceUpdateBossBar.addPlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (priceUpdateBossBar != null) {
            priceUpdateBossBar.removePlayer(event.getPlayer());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Player player = (Player) e.getPlayer();
        Inventory inv = sellInventories.get(player.getUniqueId());
        if (inv != null && e.getInventory().equals(inv)) {
            double totalSale = 0;
            List<ItemStack> unsoldItems = new ArrayList<>();
            Map<Material, Integer> soldItemsSummary = new HashMap<>();

            // 1. アイテムを集計し、合計金額を計算する
            for (ItemStack item : inv.getContents()) {
                if (item == null) continue;

                Material type = item.getType();
                if (currentPrices.containsKey(type) && currentPrices.get(type) > 0) {
                    soldItemsSummary.merge(type, item.getAmount(), Integer::sum);
                    totalSale += currentPrices.get(type) * item.getAmount();
                } else {
                    unsoldItems.add(item);
                }
            }

            if (totalSale > 0) {
                deposit(player, totalSale);

                // 2. レシートメッセージを作成する
                TextComponent mainMsg = new TextComponent(
                        ChatColor.GREEN + "売却完了！ +" + formatAmount(totalSale) + " " + moneyUnit + " ");
                TextComponent detailsMsg = new TextComponent(
                        ChatColor.YELLOW + "" + ChatColor.BOLD + "" + ChatColor.UNDERLINE + "詳細はこちら!");

                StringBuilder details = new StringBuilder();
                for (Map.Entry<Material, Integer> entry : soldItemsSummary.entrySet()) {
                    Material mat = entry.getKey();
                    int amount = entry.getValue();
                    double pricePerItem = currentPrices.get(mat);
                    double totalPriceForItem = pricePerItem * amount;

                    String itemName = itemNames.getOrDefault(mat, mat.name());

                    details.append(itemName)
                            .append(" x").append(amount)
                            .append(" = ").append(formatAmount(totalPriceForItem))
                            .append(" ").append(moneyUnit).append("\n");
                }

                detailsMsg.setHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(new ComponentBuilder(details.toString()).create())));
                mainMsg.addExtra(detailsMsg);
                player.spigot().sendMessage(mainMsg);

            } else {
                player.sendMessage(ChatColor.RED + "売れるアイテムがありませんでした。");
            }

            // 3. 売れ残ったアイテムをプレイヤーに戻す
            for (ItemStack item : unsoldItems) {
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
            if (clicked == null) return;

            ItemMeta meta = clicked.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return;

            String genreSelected = shopGenreSelections.get(player.getUniqueId());
            String clickedName = ChatColor.stripColor(meta.getDisplayName());

            if (shopConfig.contains("shop." + clickedName)) {
                openShopGenre(player, clickedName);
                return;
            }

            if (genreSelected != null) {
                if (!shopConfig.contains("shop." + genreSelected + ".items")) return;

                for (String key : shopConfig.getConfigurationSection("shop." + genreSelected + ".items").getKeys(false)) {
                    String display = shopConfig.getString("shop." + genreSelected + ".items." + key + ".display", key);
                    double price = shopConfig.getDouble("shop." + genreSelected + ".items." + key + ".price", 0);
                    int amount = shopConfig.getInt("shop." + genreSelected + ".items." + key + ".amount", 1);

                    if (clickedName.contains(display)) {
                        if (getBalance(player) >= price) {
                            withdraw(player, price);
                            Material mat = Material.matchMaterial(key);
                            if (mat != null) {
                                player.getInventory().addItem(new ItemStack(mat, amount));
                                player.sendMessage(ChatColor.GREEN + display + " を購入しました！(- " +
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
        playerNames.put(p.getUniqueId(), p.getName());
    }

    public static void deposit(Player p, double amount) {
        setBalance(p, getBalance(p) + amount);
    }

    public static void withdraw(Player p, double amount) {
        setBalance(p, Math.max(0, getBalance(p) - amount));
    }

    private void updateScoreboard() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = p.getScoreboard();
            if (sb == null || sb.getObjective(DisplaySlot.SIDEBAR) == null || !sb.getObjective(DisplaySlot.SIDEBAR).getName().equals(currentScoreboardType.name().toLowerCase())) {
                sb = Bukkit.getScoreboardManager().getNewScoreboard();
            }

            if (currentScoreboardType == ScoreboardType.RANKING) {
                updateRankingScoreboard(p, sb);
            } else {
                updatePricesScoreboard(p, sb);
            }
            p.setScoreboard(sb);
        }
    }

    private void updateRankingScoreboard(Player p, Scoreboard sb) {
        Objective obj = sb.getObjective("ranking");
        if (obj == null) {
            obj = sb.registerNewObjective("ranking", Criteria.DUMMY, ChatColor.GOLD.toString() + "KPランキング", RenderType.INTEGER);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // 古いスコアをクリア
        for (String entry : sb.getEntries()) {
            sb.resetScores(entry);
        }

        List<Player> sortedPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        sortedPlayers.sort((a, b) -> Double.compare(getBalance(b), getBalance(a)));

        int count = 1;
        boolean isListed = false;
        for (Player target : sortedPlayers) {
            if (count > 10) break;
            double bal = getBalance(target);
            String color = target.equals(p) ? ChatColor.YELLOW.toString() : ChatColor.GREEN.toString();
            String entry = color + count + "位 " + target.getName() + " " + formatAmount(bal) + moneyUnit;
            obj.getScore(entry).setScore(10 - count);
            if (target.equals(p)) isListed = true;
            count++;
        }

        if (!isListed) {
            int selfRank = sortedPlayers.indexOf(p) + 1;
            double selfBal = getBalance(p);
            obj.getScore(ChatColor.YELLOW.toString() + "~~~~~~~~~").setScore(0);
            obj.getScore(ChatColor.YELLOW.toString() + selfRank + "位 " + p.getName() + " " + formatAmount(selfBal)
                    + moneyUnit).setScore(-1);
        }
    }

    private void updatePricesScoreboard(Player p, Scoreboard sb) {
        Objective obj = sb.getObjective("prices");
        if (obj == null) {
            obj = sb.registerNewObjective("prices", Criteria.DUMMY, ChatColor.AQUA.toString() + "アイテム価格", RenderType.INTEGER);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // 古いスコアをクリア
        for (String entry : sb.getEntries()) {
            sb.resetScores(entry);
        }

        int score = 15;
        for (Material mat : scoreboardPriceMaterials) {
            if (score < 0) break;
            String itemName = itemNames.getOrDefault(mat, mat.name());
            double price = currentPrices.getOrDefault(mat, 0.0);
            String priceStr = new DecimalFormat("#,##0.00").format(price);
            obj.getScore(ChatColor.GREEN + itemName + ": " + ChatColor.WHITE + priceStr + " " + moneyUnit).setScore(score--);
        }
    }

    private void updateActionBar() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;

            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;

            for (Player other : p.getWorld().getPlayers()) {
                if (other.equals(p) || (other.getGameMode() != GameMode.SURVIVAL && other.getGameMode() != GameMode.ADVENTURE)) continue;
                double dist = p.getLocation().distance(other.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = other;
                }
            }

            String loc = "(" + p.getLocation().getBlockX() + ", " + p.getLocation().getBlockY() + ", " + p.getLocation().getBlockZ() + ")";
            String msg;
            if (nearest != null) {
                msg = ChatColor.AQUA + "location: " + loc +
                        ChatColor.YELLOW + "  @p: " + nearest.getName() +
                        ChatColor.GRAY + "  distance: " + new DecimalFormat("#0.0").format(nearestDist);
            } else {
                msg = ChatColor.AQUA + "location: " + loc + ChatColor.YELLOW + "  @p: -" + ChatColor.GRAY + "  distance: -";
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
        String s = (Math.abs(n) >= 100 ? new DecimalFormat("#,##0").format(n)
                : Math.abs(n) >= 10 ? new DecimalFormat("#,##0.0").format(n)
                : new DecimalFormat("#,##0.00").format(n));
        return s + units[idx];
    }

    private void openShopGenre(Player player, String genre) {
        Inventory inv = Bukkit.createInventory(player, 54, ChatColor.BLUE + "ショップ - " + genre);

        int slotIndex = 0;
        for (String g : shopConfig.getConfigurationSection("shop").getKeys(false)) {
            String iconName = shopConfig.getString("shop." + g + ".icon", "STONE");
            Material iconMat = Material.matchMaterial(iconName);
            if (iconMat == null) continue;

            ItemStack button = new ItemStack(iconMat);
            ItemMeta meta = button.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + g);
                button.setItemMeta(meta);
            }
            inv.setItem(slotIndex, button);
            slotIndex++;
        }

        for (int i = 9; i < 18; i++) {
            inv.setItem(i, new ItemStack(Material.GLASS_PANE));
        }

        if (shopConfig.contains("shop." + genre + ".items")) {
            int itemSlot = 18;
            for (String key : shopConfig.getConfigurationSection("shop." + genre + ".items").getKeys(false)) {
                String display = shopConfig.getString("shop." + genre + ".items." + key + ".display", key);
                int amount = shopConfig.getInt("shop." + genre + ".items." + key + ".amount", 1);
                double price = shopConfig.getDouble("shop." + genre + ".items." + key + ".price", 0);

                Material mat = Material.matchMaterial(key);
                if (mat == null) continue;

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

    private void loadBalances() {
        balancesFile = new File(getDataFolder(), "balances.yml");
        if (!balancesFile.exists()) {
            saveResource("balances.yml", false);
        }
        balancesConfig = YamlConfiguration.loadConfiguration(balancesFile);

        balances.clear();
        playerNames.clear();
        playTimes.clear();

        if (balancesConfig.isConfigurationSection("data")) {
            for (String uuidString : balancesConfig.getConfigurationSection("data").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    String path = "data." + uuidString;
                    double money = balancesConfig.getDouble(path + ".money", 0.0);
                    String name = balancesConfig.getString(path + ".playerName", "Unknown");
                    int playtime = balancesConfig.getInt(path + ".playtime", 0);

                    balances.put(uuid, money);
                    playerNames.put(uuid, name);
                    playTimes.put(uuid, playtime);

                } catch (IllegalArgumentException e) {
                    getLogger().warning("balances.ymlに無効なUUIDがあります: " + uuidString);
                }
            }
        }
        getLogger().info(balances.size() + "人分のプレイヤーデータを読み込みました。");
    }

    private void saveBalances() {
        if (balancesConfig == null || balancesFile == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            playerNames.put(uuid, player.getName());
            int playtimeInSeconds = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20;
            playTimes.put(uuid, playtimeInSeconds);
        }

        balancesConfig.set("data", null);

        Set<UUID> allUuids = new HashSet<>(balances.keySet());
        allUuids.addAll(playerNames.keySet());
        allUuids.addAll(playTimes.keySet());

        for (UUID uuid : allUuids) {
            String path = "data." + uuid.toString();
            balancesConfig.set(path + ".money", balances.getOrDefault(uuid, 0.0));
            balancesConfig.set(path + ".playerName", playerNames.getOrDefault(uuid, "Unknown"));
            balancesConfig.set(path + ".playtime", playTimes.getOrDefault(uuid, 0));
        }

        try {
            balancesConfig.save(balancesFile);
            getLogger().info(allUuids.size() + "人分のプレイヤーデータを保存しました。");
        } catch (IOException e) {
            getLogger().severe("balances.ymlへのデータ保存に失敗しました。");
            e.printStackTrace();
        }
    }

    private class PriceFluctuationTask extends BukkitRunnable {
        @Override
        public void run() {
            nextFluctuationTime = System.currentTimeMillis() + (fluctuationInterval / 20 * 1000);

            // 変動範囲が設定されているアイテムのみをリストアップ
            List<Material> fluctuatableItemsList = new ArrayList<>();
            for (Map.Entry<Material, PriceRange> entry : priceRanges.entrySet()) {
                if (entry.getValue().min < entry.getValue().max) {
                    fluctuatableItemsList.add(entry.getKey());
                }
            }

            double updateRatio = getConfig().getDouble("price-fluctuation.update-ratio", 0.2);
            Collections.shuffle(fluctuatableItemsList);
            int updateCount = (int) (fluctuatableItemsList.size() * updateRatio);

            for (int i = 0; i < updateCount; i++) {
                Material mat = fluctuatableItemsList.get(i);
                PriceRange range = priceRanges.get(mat);

                if (range == null) continue; // 念のため

                double newPrice = ThreadLocalRandom.current().nextDouble(range.min, range.max);
                BigDecimal bd = new BigDecimal(newPrice).setScale(2, RoundingMode.HALF_UP);
                currentPrices.put(mat, bd.doubleValue());
            }

            if (updateCount > 0) {
                getLogger().info(updateCount + "個のアイテム価格を更新しました。");
                if (getConfig().getBoolean("price-fluctuation.broadcast-message-on-update", true)) {
                    String message = getConfig().getString("price-fluctuation.broadcast-message", "&6【お知らせ】市場のアイテム価格が変動しました！");
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            }
        }
    }

    private class BossBarUpdateTask extends BukkitRunnable {
        @Override
        public void run() {
            long remainingMillis = nextFluctuationTime - System.currentTimeMillis();
            if (remainingMillis < 0) remainingMillis = 0;

            long totalMillis = fluctuationInterval / 20 * 1000;
            double progress = (double) remainingMillis / totalMillis;
            if (progress < 0) progress = 0;
            if (progress > 1) progress = 1;
            priceUpdateBossBar.setProgress(progress);

            long remainingSeconds = remainingMillis / 1000;
            long minutes = remainingSeconds / 60;
            long seconds = remainingSeconds % 60;
            String timeString = String.format("%02d:%02d", minutes, seconds);

            String titleTemplate = getConfig().getString("price-fluctuation.bossbar-timer.title", "&e次の価格更新まで &f%time%");
            String title = ChatColor.translateAlternateColorCodes('&', titleTemplate.replace("%time%", timeString));
            priceUpdateBossBar.setTitle(title);
        }
    }
}
