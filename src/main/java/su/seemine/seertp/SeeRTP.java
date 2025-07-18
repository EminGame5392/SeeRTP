package su.seemine.seertp;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.lang.reflect.Field;
import java.util.*;

public final class SeeRTP extends JavaPlugin implements TabExecutor, Listener {

    private FileConfiguration config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        getCommand("rtp").setExecutor(this);
        getCommand("seertp").setExecutor(this);
        getCommand("rtp").setTabCompleter(this);
        getCommand("seertp").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    public void reload() {
        reloadConfig();
        config = getConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("seertp")) {
            if (!(sender instanceof Player) || sender.hasPermission("seertp.admin")) {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    reload();
                    sender.sendMessage(ChatColor.GREEN + "[SeeRTP] Конфигурация перезагружена.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "SeeRTP работает. Используйте /seertp reload для перезагрузки.");
                }
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("rtp") && sender instanceof Player) {
            Player player = (Player) sender;
            if (args.length == 0) {
                openMenu(player);
            } else {
                String type = args[0].toLowerCase();
                if (config.contains("types." + type)) {
                    if (!player.hasPermission("seertp.type." + type)) {
                        player.sendMessage(ChatColor.RED + "У вас нет прав для этого типа телепортации.");
                    } else {
                        teleportPlayer(type, player);
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "Неизвестный тип RTP: " + type);
                }
            }
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("seertp")) {
            if (args.length == 1) return Collections.singletonList("reload");
        }

        if (cmd.getName().equalsIgnoreCase("rtp")) {
            if (args.length == 1) {
                List<String> list = new ArrayList<>();
                for (String key : config.getConfigurationSection("types").getKeys(false)) {
                    if (sender.hasPermission("seertp.type." + key)) {
                        list.add(key);
                    }
                }
                return list;
            }
        }

        return Collections.emptyList();
    }

    private void openMenu(Player player) {
        String title = ChatColor.translateAlternateColorCodes('&',
                config.getString("gui.title", "&aВыбор телепортации"));
        int size = Math.min(54, Math.max(9, config.getInt("gui.size", 54)));
        size = (size / 9) * 9;
        Inventory gui = Bukkit.createInventory(null, size, title);

        String bgMatName = config.getString("gui.background.material", "BLACK_STAINED_GLASS_PANE");
        String bgName = ChatColor.translateAlternateColorCodes('&',
                config.getString("gui.background.display_name", "&0"));
        Material bgMat = Material.matchMaterial(bgMatName.toUpperCase());
        ItemStack bgItem = new ItemStack(bgMat != null ? bgMat : Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bgItem.getItemMeta();
        if (bgMeta != null) {
            bgMeta.setDisplayName(bgName);
            bgItem.setItemMeta(bgMeta);
        }

        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, bgItem);
        }

        for (String type : config.getConfigurationSection("types").getKeys(false)) {
            if (!player.hasPermission("seertp.type." + type)) continue;
            String path = "types." + type;
            String iconRaw = config.getString(path + ".icon", "STONE");
            String name = config.getString(path + ".display_name", "&f" + type);
            List<String> lore = config.getStringList(path + ".lore");
            int slot = config.getInt(path + ".slot", -1);

            ItemStack item = getCustomHead(iconRaw);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
                List<String> coloredLore = new ArrayList<>();
                for (String line : lore) {
                    coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
                }
                meta.setLore(coloredLore);
                item.setItemMeta(meta);
            }

            if (slot >= 0 && slot < gui.getSize()) gui.setItem(slot, item);
            else gui.addItem(item);
        }

        player.openInventory(gui);
    }

    public void teleportPlayer(String type, Player player) {
        String path = "types." + type;
        List<String> allowedGroups = config.getStringList(path + ".allowed_groups");
        if (!allowedGroups.isEmpty()) {
            boolean hasGroup = allowedGroups.stream()
                    .anyMatch(group -> player.hasPermission("group." + group));
            if (!hasGroup) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        config.getString("messages.no_group_permission", "&cУ вас нет доступа к этому типу телепортации.")));
                return;
            }
        }

        World world = Bukkit.getWorld(config.getString(path + ".world"));
        int radius = config.getInt(path + ".radius", 1000);
        int minY = config.getInt(path + ".min_y", 64);
        int maxY = config.getInt(path + ".max_y", 100);
        boolean safe = config.getBoolean(path + ".safe_check", true);

        if (world == null) {
            player.sendMessage(ChatColor.RED + "Мир не найден для типа " + type);
            return;
        }

        Location target = null;
        Random rnd = new Random();
        for (int i = 0; i < 30; i++) {
            int x = rnd.nextInt(radius * 2 + 1) - radius;
            int z = rnd.nextInt(radius * 2 + 1) - radius;
            int y = world.getHighestBlockYAt(x, z);
            if (y < minY || y > maxY) continue;
            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
            if (safe && !isSafe(loc)) continue;
            target = loc;
            break;
        }

        if (target == null) {
            player.sendMessage(ChatColor.RED + "Не удалось найти безопасное место для телепортации.");
            return;
        }

        player.teleport(target);

        // Title и SubTitle
        String title = config.getString(path + ".title", "");
        String subtitle = config.getString(path + ".subtitle", "");
        title = ChatColor.translateAlternateColorCodes('&', title)
                .replace("{x}", String.valueOf(target.getBlockX()))
                .replace("{y}", String.valueOf(target.getBlockY()))
                .replace("{z}", String.valueOf(target.getBlockZ()));
        subtitle = ChatColor.translateAlternateColorCodes('&', subtitle)
                .replace("{x}", String.valueOf(target.getBlockX()))
                .replace("{y}", String.valueOf(target.getBlockY()))
                .replace("{z}", String.valueOf(target.getBlockZ()));
        player.sendTitle(title, subtitle, 10, 40, 10);

        // Эффекты
        if (config.getBoolean(path + ".effects.enabled", true)) {
            try {
                player.playSound(player.getLocation(),
                        Sound.valueOf(config.getString(path + ".effects.sound", "ENTITY_ENDERMAN_TELEPORT")), 1, 1);
            } catch (Exception ignored) {}
            try {
                player.getWorld().spawnParticle(
                        Particle.valueOf(config.getString(path + ".effects.particles", "PORTAL")),
                        player.getLocation(), 100);
            } catch (Exception ignored) {}
        }
    }

    private boolean isSafe(Location loc) {
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();
        return below.isSolid();
    }

    private ItemStack getCustomHead(String value) {
        if (!value.startsWith("basehead-")) {
            Material mat = Material.matchMaterial(value.toUpperCase());
            return new ItemStack(mat != null ? mat : Material.STONE);
        }

        String base64 = value.substring("basehead-".length());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        GameProfile profile = new GameProfile(UUID.randomUUID(), null);
        profile.getProperties().put("textures", new Property("textures", base64));
        try {
            Field field = meta.getClass().getDeclaredField("profile");
            field.setAccessible(true);
            field.set(meta, profile);
        } catch (Exception ignored) {}
        head.setItemMeta(meta);
        return head;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        String guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString("gui.title", "&aВыбор телепортации"));
        if (!event.getView().getTitle().equals(guiTitle)) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getCurrentItem() == null) return;

        for (String type : config.getConfigurationSection("types").getKeys(false)) {
            int slot = config.getInt("types." + type + ".slot", -1);
            if (event.getSlot() == slot && player.hasPermission("seertp.type." + type)) {
                player.closeInventory();
                teleportPlayer(type, player);
                return;
            }
        }
    }
}
