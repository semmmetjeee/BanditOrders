package me.semmmetje.banditorders;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public final class BanditOrders extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Pattern HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final LegacyComponentSerializer LEGACY_COMPONENTS = LegacyComponentSerializer.builder()
            .character(ChatColor.COLOR_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final List<Material> SELECTABLE_MATERIALS = Arrays.stream(Material.values())
            .filter(Material::isItem)
            .filter(material -> !material.isAir() && !material.name().startsWith("LEGACY_"))
            .sorted(Comparator.comparing(Material::name))
            .toList();

    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<String, List<ItemStack>> stored = new HashMap<>();
    private final Set<String> collectionLocks = new HashSet<>();

    private Economy economy;
    private File ordersFile;
    private YamlConfiguration gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        saveResource("gui.yml", false);
        ordersFile = new File(getDataFolder(), "orders.yml");
        reloadGui();
        hookEconomy();
        loadOrders();
        var ordersCommand = Objects.requireNonNull(getCommand("orders"), "orders command is missing from plugin.yml");
        ordersCommand.setExecutor(this);
        ordersCommand.setTabCompleter(this);
        var adminCommand = Objects.requireNonNull(getCommand("banditorders"), "banditorders command is missing from plugin.yml");
        adminCommand.setExecutor(this);
        adminCommand.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        saveOrders();
    }

    private void reloadGui() {
        File guiFile = new File(getDataFolder(), "gui.yml");
        gui = YamlConfiguration.loadConfiguration(guiFile);
        try (InputStream resource = getResource("gui.yml")) {
            if (resource != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(resource, StandardCharsets.UTF_8));
                gui.setDefaults(defaults);
                gui.options().copyDefaults(true);
                gui.save(guiFile);
            }
        } catch (IOException exception) {
            getLogger().warning("Could not migrate gui.yml: " + exception.getMessage());
        }
    }

    private void hookEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        economy = provider == null ? null : provider.getProvider();
    }

    private void loadOrders() {
        orders.clear();
        stored.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(ordersFile);
        for (String id : data.getKeys(false)) {
            if (id.equals("storage") || !data.contains(id + ".buyer")) continue;
            try {
                orders.put(id, new Order(
                        id,
                        UUID.fromString(Objects.requireNonNull(data.getString(id + ".buyer"))),
                        Material.valueOf(Objects.requireNonNull(data.getString(id + ".item"))),
                        data.getInt(id + ".total"),
                        data.getInt(id + ".remaining"),
                        data.getDouble(id + ".price")
                ));
            } catch (RuntimeException exception) {
                getLogger().warning("Skipped invalid order " + id + ": " + exception.getMessage());
            }
        }
        for (String id : orders.keySet()) stored.put(id, new ArrayList<>(itemStacks(data, "storage." + id)));
    }

    private List<ItemStack> itemStacks(YamlConfiguration configuration, String path) {
        List<?> values = configuration.getList(path, List.of());
        List<ItemStack> items = new ArrayList<>();
        for (Object value : values) if (value instanceof ItemStack stack) items.add(stack.clone());
        return items;
    }

    private void saveOrders() {
        YamlConfiguration data = new YamlConfiguration();
        for (Order order : orders.values()) {
            String path = order.id();
            data.set(path + ".buyer", order.buyer().toString());
            data.set(path + ".item", order.item().name());
            data.set(path + ".total", order.total());
            data.set(path + ".remaining", order.remaining());
            data.set(path + ".price", order.price());
            data.set("storage." + order.id(), stored.getOrDefault(order.id(), List.of()));
        }
        try {
            data.save(ordersFile);
        } catch (IOException exception) {
            getLogger().warning("Could not save orders.yml: " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("banditorders")) return adminCommand(sender, args);
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("banditorders.use")) {
            message(player, "no-permission", Map.of());
            return true;
        }
        if (args.length == 0) {
            openOrders(player, 0, "all", "", "newest");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> { return createFromCommand(player, args); }
            case "delete" -> { return deleteOrder(player, args); }
            case "search" -> {
                if (args.length == 2) {
                    String query = args[1].equalsIgnoreCase("hand")
                            ? player.getInventory().getItemInMainHand().getType().name()
                            : args[1];
                    openOrders(player, 0, "search", query, "newest");
                    return true;
                }
            }
            case "filter" -> {
                if (args.length == 2) {
                    openOrders(player, 0, "all", "", normalizeSort(args[1]));
                    return true;
                }
            }
            case "mine", "my", "own" -> {
                openMyOrders(player, 0, "newest");
                return true;
            }
            case "admin" -> {
                if (player.hasPermission("banditorders.admin")) {
                    return adminCommand(player, Arrays.copyOfRange(args, 1, args.length));
                }
            }
            default -> { }
        }

        message(player, "usage", Map.of());
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banditorders.admin")) {
            message(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            reloadGui();
            hookEconomy();
            message(sender, "reloaded", Map.of());
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            adminDeleteOrder(sender, args[1]);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            long active = orders.values().stream().filter(order -> order.remaining() > 0).count();
            message(sender, "admin-info", Map.of(
                    "orders", String.valueOf(orders.size()),
                    "active", String.valueOf(active)
            ));
            return true;
        }
        message(sender, "admin-usage", Map.of());
        return true;
    }

    private boolean adminDeleteOrder(CommandSender sender, String id) {
        Order order = orders.get(id);
        if (order == null) {
            message(sender, "order-not-found", Map.of("id", id));
            return false;
        }
        int storedItems = stored.getOrDefault(order.id(), List.of()).stream().mapToInt(ItemStack::getAmount).sum();
        if (storedItems > 0) {
            message(sender, "admin-storage-not-empty", Map.of(
                    "id", order.id(),
                    "amount", String.valueOf(storedItems)
            ));
            return false;
        }

        OfflinePlayer buyer = Bukkit.getOfflinePlayer(order.buyer());
        double refund = order.remaining() * order.price();
        if (refund > 0 && (economy == null || !economy.depositPlayer(buyer, refund).transactionSuccess())) {
            message(sender, "admin-delete-failed", Map.of("id", order.id()));
            return false;
        }

        orders.remove(order.id());
        stored.remove(order.id());
        saveOrders();
        String buyerName = buyer.getName() == null ? order.buyer().toString() : buyer.getName();
        Map<String, String> variables = Map.of(
                "id", order.id(),
                "buyer", buyerName,
                "refund", money(refund)
        );
        message(sender, "admin-deleted", variables);
        Player onlineBuyer = buyer.getPlayer();
        if (onlineBuyer != null && onlineBuyer != sender) message(onlineBuyer, "admin-deleted-owner", variables);
        return true;
    }

    private boolean createFromCommand(Player player, String[] args) {
        if (args.length < 4) {
            message(player, "invalid", Map.of());
            return true;
        }
        Material material = args[1].equalsIgnoreCase("hand")
                ? player.getInventory().getItemInMainHand().getType()
                : Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        int amount;
        double price;
        try {
            amount = Integer.parseInt(args[2]);
            price = Double.parseDouble(args[3]);
        } catch (NumberFormatException exception) {
            message(player, "invalid", Map.of());
            return true;
        }
        createOrder(player, material, amount, price);
        return true;
    }

    private boolean createOrder(Player player, Material material, int amount, double price) {
        if (economy == null) {
            message(player, "no-economy", Map.of());
            return false;
        }
        if (material == null || material.isAir() || !material.isItem() || amount < 1 || !Double.isFinite(price) || price <= 0) {
            message(player, "invalid", Map.of());
            return false;
        }
        long own = orders.values().stream().filter(order -> order.buyer().equals(player.getUniqueId()) && order.remaining() > 0).count();
        int limit = orderLimit(player);
        if (own >= limit) {
            message(player, "full", Map.of("limit", String.valueOf(limit)));
            return false;
        }
        double totalPrice = amount * price;
        if (!Double.isFinite(totalPrice) || !economy.has(player, totalPrice)) {
            message(player, "no-money", Map.of());
            return false;
        }
        EconomyResponse withdrawal = economy.withdrawPlayer(player, totalPrice);
        if (!withdrawal.transactionSuccess()) {
            message(player, "no-money", Map.of());
            return false;
        }

        Order order = new Order(UUID.randomUUID().toString().substring(0, 8), player.getUniqueId(), material, amount, amount, price);
        orders.put(order.id(), order);
        saveOrders();
        message(player, "created", Map.of(
                "amount", String.valueOf(amount),
                "item", nice(material),
                "price", money(price),
                "total", money(totalPrice)
        ));
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) openOrders(player, 0, "all", "", "newest");
        });
        return true;
    }

    private int orderLimit(Player player) {
        if (player.hasPermission("banditorders.amount.unlimited")) return Integer.MAX_VALUE;
        int highest = player.getEffectivePermissions().stream()
                .filter(PermissionAttachmentInfo::getValue)
                .map(PermissionAttachmentInfo::getPermission)
                .filter(permission -> permission.startsWith("banditorders.amount."))
                .map(permission -> permission.substring("banditorders.amount.".length()))
                .mapToInt(value -> {
                    try {
                        return Math.max(0, Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                })
                .max()
                .orElse(-1);
        return highest >= 0 ? highest : Math.max(0, getConfig().getInt("max-orders-per-player", 10));
    }

    private boolean deleteOrder(Player player, String[] args) {
        if (args.length != 2) {
            message(player, "delete-usage", Map.of());
            return true;
        }
        Order order = orders.get(args[1]);
        if (order == null || !order.buyer().equals(player.getUniqueId())) {
            message(player, "order-not-found", Map.of());
            return true;
        }
        double refund = order.remaining() * order.price();
        if (economy == null || !economy.depositPlayer(player, refund).transactionSuccess()) {
            message(player, "refund-failed", Map.of());
            return true;
        }
        orders.remove(order.id());
        stored.remove(order.id());
        saveOrders();
        message(player, "deleted", Map.of("money", money(refund)));
        return true;
    }

    private void openOrders(Player player, int requestedPage, String scope, String query, String sort) {
        int inventorySize = inventorySize("list.size", 54);
        int[] contentSlots = slots("list.content-slots", defaultOrderSlots(), inventorySize);
        List<Order> visible = filtered(scope, query, sort, player.getUniqueId());
        int pageCount = Math.max(1, (visible.size() + contentSlots.length - 1) / contentSlots.length);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        Inventory inventory = Bukkit.createInventory(new ListHolder(page, scope, query, sort), inventorySize,
                color(gui.getString("list.title", "&#d9a441&lMARKET ORDERS")));
        border(inventory);

        int from = page * contentSlots.length;
        for (int index = 0; index < contentSlots.length && from + index < visible.size(); index++) {
            Order order = visible.get(from + index);
            Map<String, String> variables = variables(order);
            List<String> lore = new ArrayList<>(replace(gui.getStringList("list.order-lore"), variables));
            if (player.hasPermission("banditorders.admin")) {
                lore.addAll(replace(configuredLore("list.admin-lore", List.of(
                        "",
                        "&cQ → Admin-delete this order",
                        "&8Order ID: %id%"
                )), variables));
            }
            inventory.setItem(contentSlots[index], item(
                    order.item(),
                    replace(gui.getString("list.order-name", "&#d9a441%item%"), variables),
                    lore,
                    null,
                    false
            ));
        }

        putButton(inventory, "list.sort", 4, Material.HOPPER, "&#d9a441&lSORT: &f%sort%",
                List.of("&7Current: &f%sort%", "&7Next: &f%next_sort%", "", "&#d9a441→ Click to change"),
                Map.of("sort", sortName(sort), "next_sort", sortName(nextSort(sort))));
        putButton(inventory, "list.buttons.previous", 45, Material.ARROW, "&fPrevious page", List.of("&7Go to page %page%."), Map.of("page", String.valueOf(Math.max(1, page))));
        putButton(inventory, "list.buttons.new-order", 48, Material.EMERALD, "&#62dc91&lNEW ORDER", List.of("&7Create a new wanted order.", "", "&#d9a441→ Click to start"), Map.of());
        putButton(inventory, "list.buttons.your-orders", 49, Material.CHEST, "&#55b7ff&lYOUR ORDERS", List.of("&7View your orders and storage.", "", "&#d9a441→ Click to open"), Map.of());
        putButton(inventory, "list.buttons.back", 50, Material.OAK_DOOR, "&fBack to market", List.of("&7Return to the main market menu.", "", "&#d9a441→ Click to go back"), Map.of());
        putButton(inventory, "list.buttons.next", 53, Material.ARROW, "&fNext page", List.of("&7Go to page %page%."), Map.of("page", String.valueOf(Math.min(pageCount, page + 2))));
        player.openInventory(inventory);
    }

    private void openMyOrders(Player player, int requestedPage, String sort) {
        int inventorySize = inventorySize("my-orders.size", 54);
        int[] contentSlots = slots("my-orders.content-slots", defaultOrderSlots(), inventorySize);
        List<Order> visible = filtered("mine", "", sort, player.getUniqueId());
        int pageCount = Math.max(1, (visible.size() + contentSlots.length - 1) / contentSlots.length);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        Inventory inventory = Bukkit.createInventory(new MyOrdersHolder(page, sort), inventorySize,
                color(gui.getString("my-orders.title", "&#55b7ff&lYOUR ORDERS")));
        border(inventory);

        int from = page * contentSlots.length;
        for (int index = 0; index < contentSlots.length && from + index < visible.size(); index++) {
            Order order = visible.get(from + index);
            Map<String, String> variables = variables(order);
            List<String> lore = new ArrayList<>(replace(configuredLore("my-orders.order-lore", List.of(
                    "&7Wanted: &#d9a441%remaining%&7/&f%total%",
                    "&7Price each: &#d9a441%price%",
                    "&7Remaining value: &#d9a441%total_price%",
                    "",
                    "&#55b7ff→ Click to open storage"
            )), variables));
            if (player.hasPermission("banditorders.admin")) {
                lore.addAll(replace(configuredLore("my-orders.admin-lore", List.of(
                        "",
                        "&cQ → Admin-delete this order",
                        "&8Order ID: %id%"
                )), variables));
            }
            inventory.setItem(contentSlots[index], item(
                    order.item(),
                    replace(gui.getString("my-orders.order-name", "&#55b7ff%item%"), variables),
                    lore,
                    null,
                    false
            ));
        }

        if (visible.isEmpty()) {
            putButton(inventory, "my-orders.empty", 22, Material.BARRIER, "&cNo orders found",
                    List.of("&7You do not have any orders yet.", "", "&#d9a441→ Create one from the orders menu"), Map.of());
        }
        putButton(inventory, "my-orders.sort", 4, Material.HOPPER, "&#55b7ff&lSORT: &f%sort%",
                List.of("&7Current: &f%sort%", "&7Next: &f%next_sort%", "", "&#55b7ff→ Click to change"),
                Map.of("sort", sortName(sort), "next_sort", sortName(nextSort(sort))));
        putButton(inventory, "my-orders.buttons.previous", 45, Material.ARROW, "&fPrevious page",
                List.of("&7Go to page %page%."), Map.of("page", String.valueOf(Math.max(1, page))));
        putButton(inventory, "my-orders.buttons.back", 49, Material.OAK_DOOR, "&#d9a441&lBACK TO ORDERS",
                List.of("&7Return to all market orders.", "", "&#d9a441→ Click to go back"), Map.of());
        putButton(inventory, "my-orders.buttons.next", 53, Material.ARROW, "&fNext page",
                List.of("&7Go to page %page%."), Map.of("page", String.valueOf(Math.min(pageCount, page + 2))));
        player.openInventory(inventory);
    }

    private void openItemSelector(Player player, int requestedPage) {
        int inventorySize = inventorySize("selection.size", 54);
        int[] contentSlots = slots("selection.content-slots", defaultSelectionSlots(), inventorySize);
        int pageCount = Math.max(1, (SELECTABLE_MATERIALS.size() + contentSlots.length - 1) / contentSlots.length);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        Inventory inventory = Bukkit.createInventory(new SelectionHolder(page), inventorySize,
                color(gui.getString("selection.title", "&#d9a441&lSELECT AN ITEM")));
        if (gui.getBoolean("selection.border.enabled", false)) border(inventory);

        int from = page * contentSlots.length;
        for (int index = 0; index < contentSlots.length && from + index < SELECTABLE_MATERIALS.size(); index++) {
            Material material = SELECTABLE_MATERIALS.get(from + index);
            Map<String, String> variables = Map.of("item", nice(material));
            String name = replace(gui.getString("selection.item.name", "&f%item%"), variables);
            List<String> lore = replace(configuredLore("selection.item.lore", List.of("", "&#d9a441→ Click to select item")), variables);
            inventory.setItem(contentSlots[index], item(material, name, lore, null, false));
        }

        putButton(inventory, "selection.buttons.previous", 45, Material.ARROW, "&fPrevious page", List.of("&7Go to page %page%."), Map.of("page", String.valueOf(Math.max(1, page))));
        putButton(inventory, "selection.buttons.info", 49, Material.BOOK, "&#d9a441&lITEMS", List.of("&7Page &f%page%&7/&f%pages%"), Map.of("page", String.valueOf(page + 1), "pages", String.valueOf(pageCount)));
        putButton(inventory, "selection.buttons.next", 53, Material.ARROW, "&fNext page", List.of("&7Go to page %page%."), Map.of("page", String.valueOf(Math.min(pageCount, page + 2))));
        player.openInventory(inventory);
    }

    private void openCollection(Player player, Order order) {
        if (!collectionLocks.add(order.id())) {
            message(player, "storage-locked", Map.of());
            return;
        }
        openCollectionPage(player, order, 0);
    }

    private void openCollectionPage(Player player, Order order, int requestedPage) {
        int inventorySize = inventorySize("storage.size", 54);
        int[] contentSlots = slots("storage.content-slots", defaultOrderSlots(), inventorySize);
        List<ItemStack> contents = stored.getOrDefault(order.id(), List.of());
        int pageCount = Math.max(1, (contents.size() + contentSlots.length - 1) / contentSlots.length);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        Map<String, String> variables = new HashMap<>(variables(order));
        variables.put("page", String.valueOf(page + 1));
        variables.put("pages", String.valueOf(pageCount));

        Inventory inventory = Bukkit.createInventory(new CollectionHolder(order.id(), page), inventorySize,
                color(replace(gui.getString("storage.title", "&#d9a441&lORDER STORAGE &8(%page%/%pages%)"), variables)));
        border(inventory);
        int from = page * contentSlots.length;
        for (int index = 0; index < contentSlots.length && from + index < contents.size(); index++) {
            ItemStack stack = contents.get(from + index);
            if (stack != null && !stack.getType().isAir()) inventory.setItem(contentSlots[index], stack.clone());
        }
        if (page > 0) {
            putButton(inventory, "storage.buttons.previous", 45, Material.ARROW, "&fPrevious page",
                    List.of("&7Go to page %page%."), Map.of("page", String.valueOf(page)));
        }
        putButton(inventory, "storage.buttons.back", 49, Material.OAK_DOOR, "&#d9a441&lBACK TO ORDERS",
                List.of("&7Return to the orders menu.", "", "&#d9a441→ Click to go back"), Map.of());
        if (page + 1 < pageCount) {
            putButton(inventory, "storage.buttons.next", 53, Material.ARROW, "&fNext page",
                    List.of("&7Go to page %page%."), Map.of("page", String.valueOf(page + 2)));
        }
        player.openInventory(inventory);
    }

    private void openDelivery(Player player, Order order) {
        int inventorySize = inventorySize("delivery.size", 27);
        Inventory inventory = Bukkit.createInventory(new DeliveryHolder(order.id()), inventorySize,
                color(gui.getString("delivery.title", "&#d9a441&lDELIVER ORDER")));
        border(inventory);
        Map<String, String> variables = variables(order);
        int infoSlot = validSlot(gui.getInt("delivery.info.slot", gui.getInt("delivery.info-slot", 4)), 4, inventorySize);
        inventory.setItem(infoSlot, configuredItem("delivery.info", order.item(), "&#d9a441&l%item%",
                List.of("&7Place matching items in the slots.", "&7They are paid when you close the menu.", "&7Remaining: &#d9a441%remaining%"), variables));
        for (int inputSlot : slots("delivery.input-slots", List.of(10, 11, 12, 13, 14, 15, 16), inventorySize)) inventory.setItem(inputSlot, null);
        putButton(inventory, "delivery.buttons.back", 22, Material.OAK_DOOR, "&#d9a441&lBACK TO ORDERS",
                List.of("&7Cancel this delivery and return", "&7all inserted items.", "", "&#d9a441→ Click to go back"), Map.of());
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof ListHolder listHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
            handleListClick(player, event.getRawSlot(), listHolder, event.getClick());
            return;
        }
        if (holder instanceof MyOrdersHolder myOrdersHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
            handleMyOrdersClick(player, event.getRawSlot(), myOrdersHolder, event.getClick());
            return;
        }
        if (holder instanceof SelectionHolder selectionHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
            handleSelectionClick(player, event.getRawSlot(), selectionHolder);
            return;
        }
        if (holder instanceof CollectionHolder collectionHolder) {
            handleCollectionClick(event, player, top, collectionHolder);
            return;
        }
        if (holder instanceof DeliveryHolder deliveryHolder) {
            if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
                if (event.getClick() == ClickType.DOUBLE_CLICK) event.setCancelled(true);
                return;
            }
            Set<Integer> inputs = asSet(slots("delivery.input-slots", List.of(10, 11, 12, 13, 14, 15, 16), top.getSize()));
            if (buttonClicked("delivery.buttons.back", event.getRawSlot(), 22, top.getSize())) {
                event.setCancelled(true);
                deliveryHolder.switching = true;
                returnInputs(player, top, inputs);
                openOrders(player, 0, "all", "", "newest");
                return;
            }
            if (!inputs.contains(event.getRawSlot())) event.setCancelled(true);
        }
    }

    private void handleCollectionClick(InventoryClickEvent event, Player player, Inventory inventory, CollectionHolder holder) {
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= inventory.getSize()) {
            if (event.getClick() == ClickType.DOUBLE_CLICK) event.setCancelled(true);
            return;
        }
        int[] contentSlots = slots("storage.content-slots", defaultOrderSlots(), inventory.getSize());

        if (holder.page > 0 && buttonClicked("storage.buttons.previous", rawSlot, 45, inventory.getSize())) {
            event.setCancelled(true);
            switchCollectionPage(player, inventory, holder, holder.page - 1);
            return;
        }
        if (buttonClicked("storage.buttons.back", rawSlot, 49, inventory.getSize())) {
            event.setCancelled(true);
            holder.switching = true;
            saveCollectionPage(inventory, holder);
            collectionLocks.remove(holder.orderId);
            openOrders(player, 0, "all", "", "newest");
            return;
        }
        int pageCount = Math.max(1, (stored.getOrDefault(holder.orderId, List.of()).size() + contentSlots.length - 1) / contentSlots.length);
        if (holder.page + 1 < pageCount && buttonClicked("storage.buttons.next", rawSlot, 53, inventory.getSize())) {
            event.setCancelled(true);
            switchCollectionPage(player, inventory, holder, holder.page + 1);
            return;
        }
        if (indexOf(contentSlots, rawSlot) < 0) event.setCancelled(true);
    }

    private void switchCollectionPage(Player player, Inventory inventory, CollectionHolder holder, int page) {
        holder.switching = true;
        saveCollectionPage(inventory, holder);
        Order order = orders.get(holder.orderId);
        if (order == null) {
            collectionLocks.remove(holder.orderId);
            openOrders(player, 0, "all", "", "newest");
            return;
        }
        openCollectionPage(player, order, page);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        Set<Integer> allowed;
        if (holder instanceof CollectionHolder) {
            allowed = asSet(slots("storage.content-slots", defaultOrderSlots(), top.getSize()));
        } else if (holder instanceof DeliveryHolder) {
            allowed = asSet(slots("delivery.input-slots", List.of(10, 11, 12, 13, 14, 15, 16), top.getSize()));
        } else if (holder instanceof ListHolder || holder instanceof MyOrdersHolder || holder instanceof SelectionHolder) {
            allowed = Set.of();
        } else {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize() && !allowed.contains(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleListClick(Player player, int slot, ListHolder holder, ClickType click) {
        int size = inventorySize("list.size", 54);
        if (buttonClicked("list.sort", slot, 4, size)) {
            openOrders(player, 0, holder.scope(), holder.query(), nextSort(holder.sort()));
            return;
        }
        if (buttonClicked("list.buttons.previous", slot, 45, size)) {
            if (holder.page() > 0) openOrders(player, holder.page() - 1, holder.scope(), holder.query(), holder.sort());
            return;
        }
        if (buttonClicked("list.buttons.next", slot, 53, size)) {
            openOrders(player, holder.page() + 1, holder.scope(), holder.query(), holder.sort());
            return;
        }
        if (buttonClicked("list.buttons.new-order", slot, 48, size)) {
            openItemSelector(player, 0);
            return;
        }
        if (buttonClicked("list.buttons.your-orders", slot, 49, size)) {
            openMyOrders(player, 0, "newest");
            return;
        }
        if (buttonClicked("list.buttons.back", slot, 50, size)) {
            player.closeInventory();
            String command = gui.getString("list.buttons.back.command", "markt");
            if (command != null && !command.isBlank()) player.performCommand(command.startsWith("/") ? command.substring(1) : command);
            return;
        }

        int[] contentSlots = slots("list.content-slots", defaultOrderSlots(), size);
        int index = indexOf(contentSlots, slot);
        List<Order> visible = filtered(holder.scope(), holder.query(), holder.sort(), player.getUniqueId());
        int orderIndex = holder.page() * contentSlots.length + index;
        if (index < 0 || orderIndex < 0 || orderIndex >= visible.size()) return;
        Order order = visible.get(orderIndex);
        if ((click == ClickType.DROP || click == ClickType.CONTROL_DROP)
                && player.hasPermission("banditorders.admin")) {
            if (adminDeleteOrder(player, order.id())) {
                Bukkit.getScheduler().runTask(this,
                        () -> openOrders(player, holder.page(), holder.scope(), holder.query(), holder.sort()));
            }
            return;
        }
        if (order.buyer().equals(player.getUniqueId())) openCollection(player, order);
        else if (order.remaining() > 0) openDelivery(player, order);
    }

    private void handleMyOrdersClick(Player player, int slot, MyOrdersHolder holder, ClickType click) {
        int size = inventorySize("my-orders.size", 54);
        if (buttonClicked("my-orders.sort", slot, 4, size)) {
            openMyOrders(player, 0, nextSort(holder.sort()));
            return;
        }
        if (buttonClicked("my-orders.buttons.previous", slot, 45, size)) {
            if (holder.page() > 0) openMyOrders(player, holder.page() - 1, holder.sort());
            return;
        }
        if (buttonClicked("my-orders.buttons.next", slot, 53, size)) {
            openMyOrders(player, holder.page() + 1, holder.sort());
            return;
        }
        if (buttonClicked("my-orders.buttons.back", slot, 49, size)) {
            openOrders(player, 0, "all", "", "newest");
            return;
        }
        int[] contentSlots = slots("my-orders.content-slots", defaultOrderSlots(), size);
        int index = indexOf(contentSlots, slot);
        List<Order> visible = filtered("mine", "", holder.sort(), player.getUniqueId());
        int orderIndex = holder.page() * contentSlots.length + index;
        if (index < 0 || orderIndex < 0 || orderIndex >= visible.size()) return;
        Order order = visible.get(orderIndex);
        if ((click == ClickType.DROP || click == ClickType.CONTROL_DROP)
                && player.hasPermission("banditorders.admin")) {
            if (adminDeleteOrder(player, order.id())) {
                Bukkit.getScheduler().runTask(this,
                        () -> openMyOrders(player, holder.page(), holder.sort()));
            }
            return;
        }
        openCollection(player, order);
    }

    private void handleSelectionClick(Player player, int slot, SelectionHolder holder) {
        int size = inventorySize("selection.size", 54);
        if (buttonClicked("selection.buttons.previous", slot, 45, size)) {
            if (holder.page() > 0) openItemSelector(player, holder.page() - 1);
            return;
        }
        if (buttonClicked("selection.buttons.next", slot, 53, size)) {
            openItemSelector(player, holder.page() + 1);
            return;
        }
        if (buttonClicked("selection.buttons.info", slot, 49, size)) return;

        int[] contentSlots = slots("selection.content-slots", defaultSelectionSlots(), size);
        int localIndex = indexOf(contentSlots, slot);
        int materialIndex = holder.page() * contentSlots.length + localIndex;
        if (localIndex < 0 || materialIndex < 0 || materialIndex >= SELECTABLE_MATERIALS.size()) return;
        Material material = SELECTABLE_MATERIALS.get(materialIndex);
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> openOrderDialog(player, material,
                gui.getString("order-dialog.amount.initial", "1"),
                gui.getString("order-dialog.price.initial", "")));
    }

    private void openOrderDialog(Player player, Material material, String initialAmount, String initialPrice) {
        Map<String, String> variables = Map.of("item", nice(material));
        int inputWidth = Math.max(1, Math.min(1024, gui.getInt("order-dialog.input-width", 300)));
        int buttonWidth = Math.max(1, Math.min(1024, gui.getInt("order-dialog.button-width", 150)));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(component(replace(gui.getString("order-dialog.title",
                                "&#d9a441&lCREATE %item% ORDER"), variables)))
                        .canCloseWithEscape(true)
                        .inputs(List.of(
                                DialogInput.text("amount", component(gui.getString("order-dialog.amount.label", "&fAmount")))
                                        .width(inputWidth)
                                        .initial(initialAmount == null ? "" : initialAmount)
                                        .maxLength(10)
                                        .build(),
                                DialogInput.text("price", component(gui.getString("order-dialog.price.label", "&fPrice per item")))
                                        .width(inputWidth)
                                        .initial(initialPrice == null ? "" : initialPrice)
                                        .maxLength(32)
                                        .build()
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                component(gui.getString("order-dialog.confirm.name", "&#62dc91&lCREATE ORDER")),
                                component(gui.getString("order-dialog.confirm.tooltip", "&fCreate this order.")),
                                buttonWidth,
                                DialogAction.customClick((response, audience) -> {
                                    if (!(audience instanceof Player target)) return;
                                    String amount = response.getText("amount");
                                    String price = response.getText("price");
                                    Bukkit.getScheduler().runTask(this, () -> handleOrderDialogInput(
                                            target, material, amount == null ? "" : amount, price == null ? "" : price));
                                }, singleUseCallback())
                        ),
                        ActionButton.create(
                                component(gui.getString("order-dialog.cancel.name", "&#e3262e&lCANCEL")),
                                component(gui.getString("order-dialog.cancel.tooltip", "&fCancel this order.")),
                                buttonWidth,
                                DialogAction.customClick((response, audience) -> {
                                    if (audience instanceof Player target) {
                                        Bukkit.getScheduler().runTask(this,
                                                () -> message(target, "builder-cancelled", Map.of()));
                                    }
                                }, singleUseCallback())
                        )
                )));
        player.showDialog(dialog);
    }

    private ClickCallback.Options singleUseCallback() {
        return ClickCallback.Options.builder().uses(1).build();
    }

    private void handleOrderDialogInput(Player player, Material material, String amountInput, String priceInput) {
        int amount;
        try {
            amount = Integer.parseInt(amountInput.trim());
            if (amount < 1) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            message(player, "builder-invalid-amount", Map.of());
            openOrderDialog(player, material, amountInput, priceInput);
            return;
        }

        double price;
        try {
            price = Double.parseDouble(priceInput.trim().replace(',', '.'));
            if (!Double.isFinite(price) || price <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            message(player, "builder-invalid-price", Map.of());
            openOrderDialog(player, material, amountInput, priceInput);
            return;
        }
        createOrder(player, material, amount, price);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof CollectionHolder holder) {
            if (!holder.switching) {
                saveCollectionPage(event.getInventory(), holder);
                collectionLocks.remove(holder.orderId);
            }
            return;
        }
        if (!(event.getInventory().getHolder() instanceof DeliveryHolder holder) || !(event.getPlayer() instanceof Player player)) return;
        if (holder.switching) return;
        finishDelivery(player, event.getInventory(), holder.orderId());
    }

    private void saveCollectionPage(Inventory inventory, CollectionHolder holder) {
        int[] contentSlots = slots("storage.content-slots", defaultOrderSlots(), inventory.getSize());
        List<ItemStack> visibleItems = new ArrayList<>();
        for (int slot : contentSlots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir()) visibleItems.add(stack.clone());
        }

        List<ItemStack> allItems = new ArrayList<>(stored.getOrDefault(holder.orderId, List.of()));
        int from = Math.min(holder.page * contentSlots.length, allItems.size());
        int to = Math.min(from + contentSlots.length, allItems.size());
        allItems.subList(from, to).clear();
        allItems.addAll(from, visibleItems);
        stored.put(holder.orderId, allItems);
        saveOrders();
    }

    private void finishDelivery(Player player, Inventory inventory, String orderId) {
        Set<Integer> inputSlots = asSet(slots("delivery.input-slots", List.of(10, 11, 12, 13, 14, 15, 16), inventory.getSize()));
        Order order = orders.get(orderId);
        if (order == null) {
            returnInputs(player, inventory, inputSlots);
            return;
        }
        int delivered = 0;
        List<ItemStack> deposited = new ArrayList<>();

        for (int slot : inputSlots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir() || stack.getType() != order.item() || delivered >= order.remaining()) continue;
            int taken = Math.min(stack.getAmount(), order.remaining() - delivered);
            ItemStack copy = stack.clone();
            copy.setAmount(taken);
            deposited.add(copy);
            delivered += taken;
            stack.setAmount(stack.getAmount() - taken);
            if (stack.getAmount() <= 0) inventory.setItem(slot, null);
        }

        returnInputs(player, inventory, inputSlots);
        if (delivered < 1) return;
        double payment = delivered * order.price();
        if (economy == null || !economy.depositPlayer(player, payment).transactionSuccess()) {
            for (ItemStack stack : deposited) giveOrDrop(player, stack);
            message(player, "payment-failed", Map.of());
            return;
        }

        stored.computeIfAbsent(order.id(), ignored -> new ArrayList<>()).addAll(deposited);
        orders.put(order.id(), new Order(order.id(), order.buyer(), order.item(), order.total(), order.remaining() - delivered, order.price()));
        saveOrders();
        message(player, "paid", Map.of("amount", String.valueOf(delivered), "item", nice(order.item()), "money", money(payment)));
    }

    private void returnInputs(Player player, Inventory inventory, Collection<Integer> slots) {
        for (int slot : slots) {
            if (slot < 0 || slot >= inventory.getSize()) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            inventory.setItem(slot, null);
            giveOrDrop(player, stack);
        }
    }

    private void giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private List<Order> filtered(String scope, String query, String sort, UUID viewer) {
        List<Order> result = new ArrayList<>(orders.values());
        if (scope.equals("mine")) result.removeIf(order -> !order.buyer().equals(viewer));
        else result.removeIf(order -> order.remaining() <= 0);
        if (scope.equals("search")) result.removeIf(order -> !order.item().name().equalsIgnoreCase(query));

        switch (normalizeSort(sort)) {
            case "oldest" -> { }
            case "price-desc" -> result.sort(Comparator.comparingDouble(Order::price).reversed());
            case "price-asc" -> result.sort(Comparator.comparingDouble(Order::price));
            default -> java.util.Collections.reverse(result);
        }
        return result;
    }

    private String normalizeSort(String sort) {
        if (sort == null) return "newest";
        return switch (sort.toLowerCase(Locale.ROOT)) {
            case "oldest", "oudste" -> "oldest";
            case "price", "expensive", "duurste", "price-desc" -> "price-desc";
            case "cheap", "cheapest", "goedkoopste", "price-asc" -> "price-asc";
            default -> "newest";
        };
    }

    private String nextSort(String sort) {
        return switch (normalizeSort(sort)) {
            case "newest" -> "oldest";
            case "oldest" -> "price-desc";
            case "price-desc" -> "price-asc";
            default -> "newest";
        };
    }

    private String sortName(String sort) {
        String normalized = normalizeSort(sort);
        return gui.getString("list.sort.names." + normalized, switch (normalized) {
            case "oldest" -> "Oudste";
            case "price-desc" -> "Duurste per item";
            case "price-asc" -> "Goedkoopste per item";
            default -> "Nieuwste";
        });
    }

    private void border(Inventory inventory) {
        if (!gui.getBoolean("border.enabled", true)) return;
        ItemStack border = configuredItem("border", Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), Map.of());
        int rows = inventory.getSize() / 9;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) inventory.setItem(slot, border);
        }
    }

    private void putButton(Inventory inventory, String path, int fallbackSlot, Material fallbackMaterial,
                           String fallbackName, List<String> fallbackLore, Map<String, String> variables) {
        if (!gui.getBoolean(path + ".enabled", true)) return;
        int slot = validSlot(gui.getInt(path + ".slot", fallbackSlot), fallbackSlot, inventory.getSize());
        inventory.setItem(slot, configuredItem(path, fallbackMaterial, fallbackName, fallbackLore, variables));
    }

    private boolean buttonClicked(String path, int clickedSlot, int fallbackSlot, int size) {
        return gui.getBoolean(path + ".enabled", true)
                && clickedSlot == validSlot(gui.getInt(path + ".slot", fallbackSlot), fallbackSlot, size);
    }

    private ItemStack configuredItem(String path, Material fallbackMaterial, String fallbackName,
                                     List<String> fallbackLore, Map<String, String> variables) {
        Material material = Material.matchMaterial(gui.getString(path + ".material", fallbackMaterial.name()));
        if (material == null || !material.isItem() || material.isAir()) material = fallbackMaterial;
        String name = replace(gui.getString(path + ".name", fallbackName), variables);
        List<String> lore = replace(configuredLore(path + ".lore", fallbackLore), variables);
        Integer customModelData = gui.contains(path + ".custom-model-data") ? gui.getInt(path + ".custom-model-data") : null;
        boolean glow = gui.getBoolean(path + ".glow", false);
        return item(material, name, lore, customModelData, glow);
    }

    private List<String> configuredLore(String path, List<String> fallback) {
        return gui.contains(path) ? gui.getStringList(path) : fallback;
    }

    private ItemStack item(Material material, String name, List<String> lore, Integer customModelData, boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        if (customModelData != null) meta.setCustomModelData(customModelData);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    private Map<String, String> variables(Order order) {
        String buyer = Bukkit.getOfflinePlayer(order.buyer()).getName();
        return Map.of(
                "id", order.id(),
                "item", nice(order.item()),
                "buyer", buyer == null ? "Unknown" : buyer,
                "remaining", String.valueOf(order.remaining()),
                "total", String.valueOf(order.total()),
                "price", money(order.price()),
                "total_price", money(order.remaining() * order.price())
        );
    }

    private List<String> replace(List<String> lines, Map<String, String> variables) {
        return lines.stream().map(line -> replace(line, variables)).toList();
    }

    private String replace(String input, Map<String, String> variables) {
        String result = input == null ? "" : input;
        for (Map.Entry<String, String> entry : variables.entrySet()) result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        return result;
    }

    private String nice(Material material) {
        return Arrays.stream(material.name().split("_"))
                .map(part -> part.substring(0, 1) + part.substring(1).toLowerCase(Locale.ROOT))
                .reduce((first, second) -> first + " " + second)
                .orElse(material.name());
    }

    private String money(double value) {
        return economy == null ? String.format(Locale.US, "%.2f", value) : economy.format(value);
    }

    private String color(String input) {
        if (input == null || input.isEmpty()) return "";
        Matcher matcher = HEX.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("&x");
            for (char character : matcher.group(1).toCharArray()) replacement.append('&').append(character);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(output);
        return ChatColor.translateAlternateColorCodes('&', output.toString());
    }

    private Component component(String input) {
        return LEGACY_COMPONENTS.deserialize(color(input));
    }

    private void message(CommandSender sender, String key, Map<String, String> variables) {
        String configured = getConfig().getString("messages." + key, "");
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(color(replace(configured, variables).replace("%prefix%", prefix)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("banditorders")) {
            if (!sender.hasPermission("banditorders.admin")) return List.of();
            if (args.length == 1) return matches(args[0], List.of("reload", "delete", "info", "help"));
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
                return matches(args[1], new ArrayList<>(orders.keySet()));
            }
            return List.of();
        }
        if (!(sender instanceof Player player) || !player.hasPermission("banditorders.use")) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("create", "delete", "search", "filter", "mine"));
            if (player.hasPermission("banditorders.admin")) options.add("admin");
            return matches(args[0], options);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("filter")) {
            return matches(args[1], List.of("newest", "oldest", "price-desc", "price-asc"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            List<String> ownIds = orders.values().stream()
                    .filter(order -> order.buyer().equals(player.getUniqueId()))
                    .map(Order::id)
                    .toList();
            return matches(args[1], ownIds);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && player.hasPermission("banditorders.admin")) {
            return matches(args[1], List.of("reload", "delete", "info", "help"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("delete") && player.hasPermission("banditorders.admin")) {
            return matches(args[2], new ArrayList<>(orders.keySet()));
        }
        return List.of();
    }

    private List<String> matches(String input, List<String> options) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private int inventorySize(String path, int fallback) {
        int value = gui.getInt(path, fallback);
        return value >= 9 && value <= 54 && value % 9 == 0 ? value : fallback;
    }

    private int validSlot(int value, int fallback, int size) {
        return value >= 0 && value < size ? value : Math.max(0, Math.min(fallback, size - 1));
    }

    private int[] slots(String path, List<Integer> fallback, int size) {
        List<Integer> configured = gui.contains(path) ? gui.getIntegerList(path) : fallback;
        int[] result = configured.stream().filter(slot -> slot >= 0 && slot < size).distinct().mapToInt(Integer::intValue).toArray();
        if (result.length > 0) return result;
        return fallback.stream().filter(slot -> slot >= 0 && slot < size).distinct().mapToInt(Integer::intValue).toArray();
    }

    private List<Integer> defaultOrderSlots() {
        return List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);
    }

    private List<Integer> defaultSelectionSlots() {
        return IntStream.range(0, 45).boxed().toList();
    }

    private int indexOf(int[] values, int target) {
        for (int index = 0; index < values.length; index++) if (values[index] == target) return index;
        return -1;
    }

    private Set<Integer> asSet(int[] values) {
        Set<Integer> result = new HashSet<>();
        for (int value : values) result.add(value);
        return result;
    }

    private record Order(String id, UUID buyer, Material item, int total, int remaining, double price) { }
    private record ListHolder(int page, String scope, String query, String sort) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private record MyOrdersHolder(int page, String sort) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private record SelectionHolder(int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static final class CollectionHolder implements InventoryHolder {
        private final String orderId;
        private final int page;
        private boolean switching;

        private CollectionHolder(String orderId, int page) {
            this.orderId = orderId;
            this.page = page;
        }

        private String orderId() { return orderId; }
        @Override public Inventory getInventory() { return null; }
    }

    private static final class DeliveryHolder implements InventoryHolder {
        private final String orderId;
        private boolean switching;

        private DeliveryHolder(String orderId) {
            this.orderId = orderId;
        }

        private String orderId() { return orderId; }
        @Override public Inventory getInventory() { return null; }
    }
}
