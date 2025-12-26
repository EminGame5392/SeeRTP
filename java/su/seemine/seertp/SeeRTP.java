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
import org.bukkit.scheduler.BukkitRunnable;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SeeRTP extends JavaPlugin implements TabExecutor, Listener {

    private FileConfiguration config;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> teleportTasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        getCommand("rtp").setExecutor(this);
        getCommand("seertp").setExecutor(this);
        getCommand("rtp").setTabCompleter(this);
        getCommand("seertp").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        saveResource("config.yml", false);
    }

    public void reload() {
        reloadConfig();
        config = getConfig();
        cooldowns.clear();
        teleportTasks.values().forEach(task -> {
            if (task != null) task.cancel();
        });
        teleportTasks.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("seertp")) {
            if (sender instanceof Player && !sender.hasPermission("seertp.admin")) {
                sender.sendMessage(ChatColor.RED + "У вас нет прав для использования этой команды.");
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reload();
                sender.sendMessage(ChatColor.GREEN + "[SeeRTP] Конфигурация перезагружена.");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "SeeRTP v" + getDescription().getVersion());
                sender.sendMessage(ChatColor.YELLOW + "Используйте /seertp reload для перезагрузки.");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("rtp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эту команду может использовать только игрок!");
                return true;
            }

            Player player = (Player) sender;

            if (args.length == 0) {
                openMenu(player);
            } else {
                String type = args[0].toLowerCase();
                if (config.contains("types." + type)) {
                    processRTP(type, player);
                } else {
                    player.sendMessage(ChatColor.RED + "Неизвестный тип RTP: " + type);
                }
            }
            return true;
        }

        return false;
    }

    private void processRTP(String type, Player player) {
        if (!player.hasPermission("seertp.type." + type)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    config.getString("messages.no_permission", "&cУ вас нет прав для этого типа телепортации.")));
            return;
        }

        String path = "types." + type;
        List<String> allowedGroups = config.getStringList(path + ".allowed_groups");
        if (!allowedGroups.isEmpty()) {
            boolean hasGroup = false;
            for (String group : allowedGroups) {
                if (player.hasPermission("group." + group)) {
                    hasGroup = true;
                    break;
                }
            }
            if (!hasGroup) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        config.getString("messages.no_group_permission", "&cУ вас нет доступа к этому типу телепортации.")));
                return;
            }
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        long cooldownTime = getCooldownTime(type, player) * 1000L;

        if (cooldowns.containsKey(playerId)) {
            long remaining = (cooldowns.get(playerId) + cooldownTime) - currentTime;
            if (remaining > 0) {
                long seconds = remaining / 1000;
                String message = ChatColor.translateAlternateColorCodes('&',
                                config.getString("messages.cooldown", "&cПодождите {time} секунд перед следующим RTP."))
                        .replace("{time}", String.valueOf(seconds));
                player.sendMessage(message);
                return;
            }
        }

        int delay = config.getInt(path + ".pre_teleport_delay", 5);
        String teleportingMsg = ChatColor.translateAlternateColorCodes('&',
                config.getString("messages.teleporting", "&aТелепортация..."));

        player.sendMessage(teleportingMsg);

        BukkitRunnable task = new BukkitRunnable() {
            int count = delay;

            @Override
            public void run() {
                if (count > 0) {
                    player.sendTitle(ChatColor.YELLOW + "Телепортация через " + count + "...", "", 0, 20, 0);
                    count--;
                } else {
                    this.cancel();
                    teleportTasks.remove(playerId);

                    cooldowns.put(playerId, System.currentTimeMillis());

                    teleportPlayer(type, player);
                }
            }
        };

        task.runTaskTimer(this, 0L, 20L);
        teleportTasks.put(playerId, task);
    }

    private long getCooldownTime(String type, Player player) {
        String path = "types." + type;
        List<String> groupDelays = config.getStringList(path + ".group_delays");

        for (String delayEntry : groupDelays) {
            String[] parts = delayEntry.split(":");
            if (parts.length == 2) {
                String group = parts[0].trim();
                try {
                    int delay = Integer.parseInt(parts[1].trim());
                    if (player.hasPermission("group." + group)) {
                        return delay;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return config.getInt(path + ".default_delay", 300);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (cmd.getName().equalsIgnoreCase("seertp")) {
            if (args.length == 1) {
                completions.add("reload");
            }
        }

        if (cmd.getName().equalsIgnoreCase("rtp")) {
            if (args.length == 1) {
                for (String key : config.getConfigurationSection("types").getKeys(false)) {
                    if (sender.hasPermission("seertp.type." + key)) {
                        completions.add(key);
                    }
                }
            }
        }

        return completions;
    }

    private void openMenu(Player player) {
        if (!player.hasPermission("seertp.menu")) {
            player.sendMessage(ChatColor.RED + "У вас нет прав для открытия меню RTP.");
            return;
        }

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

                long cooldown = getCooldownTime(type, player);
                coloredLore.add(ChatColor.GRAY + "Кулдаун: " + ChatColor.YELLOW + cooldown + " сек");

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
        World world = Bukkit.getWorld(config.getString(path + ".world", "world"));
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
        for (int i = 0; i < 50; i++) { // Увеличиваем количество попыток
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

        playPreTeleportEffects(player);

        player.teleport(target);

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

        playPostTeleportEffects(player, type);

        player.sendMessage(ChatColor.GREEN + "Вы были телепортированы на координаты: " +
                target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ());
    }

    private void playPreTeleportEffects(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 1.0f, 1.0f);
        player.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 50);
    }

    private void playPostTeleportEffects(Player player, String type) {
        String path = "types." + type;

        if (config.getBoolean(path + ".effects.enabled", true)) {
            try {
                String soundName = config.getString(path + ".effects.sound", "ENTITY_ENDERMAN_TELEPORT");
                Sound sound = getSound(soundName);
                if (sound != null) {
                    player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                }
            } catch (Exception ignored) {}

            try {
                String particleName = config.getString(path + ".effects.particles", "PORTAL");
                Particle particle = getParticle(particleName);
                if (particle != null) {
                    player.getWorld().spawnParticle(particle, player.getLocation(), 100, 0.5, 1, 0.5, 0.1);
                }
            } catch (Exception ignored) {}
        }
    }

    private Sound getSound(String soundName) {
        try {
            return Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException e) {
            Map<String, String> soundMap = new HashMap<>();
            soundMap.put("ENTITY_ENDERMAN_TELEPORT", "ENTITY_ENDERMAN_TELEPORT");
            soundMap.put("ENTITY_BLAZE_SHOOT", "ENTITY_BLAZE_SHOOT");
            soundMap.put("BLOCK_PORTAL_TRAVEL", "BLOCK_PORTAL_TRAVEL");

            String mappedSound = soundMap.get(soundName.toUpperCase());
            if (mappedSound != null) {
                try {
                    return Sound.valueOf(mappedSound);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    private Particle getParticle(String particleName) {
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            Map<String, String> particleMap = new HashMap<>();
            particleMap.put("PORTAL", "PORTAL");
            particleMap.put("FLAME", "FLAME");
            particleMap.put("SMOKE_NORMAL", "SMOKE_NORMAL");
            particleMap.put("CLOUD", "CLOUD");

            String mappedParticle = particleMap.get(particleName.toUpperCase());
            if (mappedParticle != null) {
                try {
                    return Particle.valueOf(mappedParticle);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    private boolean isSafe(Location loc) {
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();
        Material at = loc.getBlock().getType();
        Material above = loc.clone().add(0, 1, 0).getBlock().getType();

        return below.isSolid() &&
                (at == Material.AIR || at == Material.CAVE_AIR || at == Material.VOID_AIR) &&
                (above == Material.AIR || above == Material.CAVE_AIR || above == Material.VOID_AIR);
    }

    private ItemStack getCustomHead(String value) {
        if (!value.startsWith("basehead-")) {
            Material mat = Material.matchMaterial(value.toUpperCase());
            if (mat == null) {
                mat = Material.STONE;
            }
            return new ItemStack(mat);
        }

        String base64 = value.substring("basehead-".length());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();

        try {
            GameProfile profile = new GameProfile(UUID.randomUUID(), null);
            profile.getProperties().put("textures", new Property("textures", base64));

            Field profileField = meta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(meta, profile);
        } catch (Exception e) {
            getLogger().warning("Не удалось создать кастомную голову: " + e.getMessage());
            return new ItemStack(Material.STONE);
        }

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

        if (event.getClickedInventory() == null ||
                event.getCurrentItem() == null ||
                event.getCurrentItem().getType() == Material.AIR) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();

        String bgMatName = config.getString("gui.background.material", "BLACK_STAINED_GLASS_PANE");
        Material bgMat = Material.matchMaterial(bgMatName.toUpperCase());
        if (bgMat != null && clicked.getType() == bgMat) {
            return;
        }

        for (String type : config.getConfigurationSection("types").getKeys(false)) {
            int slot = config.getInt("types." + type + ".slot", -1);
            if ((event.getSlot() == slot ||
                    (slot == -1 && clicked.getItemMeta() != null &&
                            clicked.getItemMeta().getDisplayName().contains(type)))) {

                if (player.hasPermission("seertp.type." + type)) {
                    player.closeInventory();
                    processRTP(type, player);
                } else {
                    player.sendMessage(ChatColor.RED + "У вас нет прав для этого типа телепортации.");
                }
                return;
            }
        }
    }

    @Override
    public void onDisable() {
        teleportTasks.values().forEach(task -> {
            if (task != null) {
                task.cancel();
            }
        });
        teleportTasks.clear();
        cooldowns.clear();
    }
}