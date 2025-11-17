package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

// Import sự kiện PlayerLoginEvent
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TazAntixRAYPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    public final Map<UUID, Boolean> playerHiddenState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> refreshCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> limitedAreaSmartRefreshTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> limitedAreaEmergencyCooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> teleportGracePeriod = ConcurrentHashMap.newKeySet();
    private static TazAntixRAYPlugin instance;
    private WrappedBlockState replacementBlockState;
    private boolean packetEventsInitialized = false;

    private String replacementMode = "DECEPTIVE";
    private boolean debugMode = false;
    private int refreshCooldownMillis = 3000;
    private Set<String> whitelistedWorlds = new HashSet<>();

    private boolean limitedAreaEnabled = false;
    private int limitedAreaChunkRadius = 3;
    private boolean limitedAreaSmartRefreshEnabled = true;
    private double limitedAreaSmartRefreshMinDistance = 2.5D;
    private float limitedAreaSmartRefreshMinRotation = 25F;
    private int limitedAreaSmartRefreshCooldownMillis = 250;
    private double limitedAreaEmergencyDistanceSquared = 0D;
    private int limitedAreaEmergencyCooldownMillis = 250;
    private int limitedAreaSightlineLookaheadChunks = 1;
    private boolean limitedAreaEnderpearlRevealEnabled = true;
    private boolean undergroundProtectionEnabled = true;
    private boolean hideEntitiesEnabled = true;
    private int entityCheckIntervalTicks;

    private int gradualRefreshDelayTicks;
    private int chunksPerTick;
    private int limitedAreaUpdateDelayTicks;

    private boolean joinPacingEnabled;
    private int joinPacingInitialDelay;
    private int joinPacingChunksPerInterval;
    private int joinPacingIntervalTicks;

    private final Map<UUID, List<WrappedTask>> gradualRefreshTasks = new ConcurrentHashMap<>();

    private FileConfiguration langConfig;

    private FoliaOptimizer foliaOptimizer;
    private PaperOptimizer paperOptimizer;
    private GeyserFloodgateSupport geyserFloodgateSupport;
    private FoliaLib foliaLib;

    @Override
    public void onEnable() {
        instance = this;
        this.foliaLib = new FoliaLib(this);
        saveDefaultConfig();

        String platform = foliaLib.isFolia() ? "Folia" : "Paper/Spigot";
        for (String line : MessageFormatter.createStartupBanner(getDescription().getVersion(), platform)) {
            getServer().getConsoleSender().sendMessage(MessageFormatter.format(line));
        }

        if (foliaLib.isFolia()) {
            foliaOptimizer = new FoliaOptimizer(this);
        } else {
            paperOptimizer = new PaperOptimizer(this);
        }

        geyserFloodgateSupport = new GeyserFloodgateSupport(this);
        loadConfigValues();

        final PacketEventsAPI packetEventsAPI = PacketEvents.getAPI();
        if (packetEventsAPI == null) {
            getLogger().severe("PacketEvents API not found! Make sure the PacketEvents plugin is installed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!packetEventsAPI.isLoaded()) {
            try {
                packetEventsAPI.load();
            } catch (Exception ex) {
                getLogger().severe("PacketEvents API failed to load. Disabling plugin.");
                ex.printStackTrace();
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        registerCommands();

        startSupervisorTask();
        startEntityHidingTask();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isWorldWhitelisted(player.getWorld().getName())) {
                handlePlayerInitialState(player);
            }
        }

        schedulePacketEventsInitialization(packetEventsAPI);
    }

    private void startEntityHidingTask() {
        if (!isHideEntitiesEnabled()) return;

        // Tác vụ này chạy bất đồng bộ để lặp qua người chơi mà không ảnh hưởng đến tick chính.
        getFoliaLib().getScheduler().runTimerAsync((task) -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.isOnline() || !isWorldWhitelisted(player.getWorld().getName())) {
                    continue;
                }

                // Chuyển việc lấy thực thể và xử lý vào luồng an toàn của người chơi đó.
                getFoliaLib().getScheduler().runAtEntity(player, (playerTask) -> {
                    if (!player.isOnline()) return; // Kiểm tra lại trạng thái người chơi

                    boolean isPlayerHidingState = playerHiddenState.getOrDefault(player.getUniqueId(), false);
                    Location playerLocation = player.getLocation();
                    int hideBelowY = getConfig().getInt("antixray.hide-below-y", 16);

                    // Lệnh này giờ đã an toàn vì nó chạy trên đúng luồng của khu vực người chơi.
                    Collection<Entity> nearbyEntities = player.getWorld().getNearbyEntities(playerLocation, 64, 64, 64);

                    for (Entity entity : nearbyEntities) {
                        if (entity.equals(player) || entity instanceof Player) {
                            continue;
                        }

                        boolean shouldHide = false;
                        Location entityLocation = entity.getLocation();

                        if (isPlayerHidingState) {
                            if (entityLocation.getY() <= hideBelowY) {
                                shouldHide = true;
                            }
                        } else if (isLimitedAreaEnabled()) {
                            if (!AntiXrayUtils.isBlockInLimitedArea(player, entityLocation.getBlockX(), entityLocation.getBlockZ(), getLimitedAreaChunkRadius())) {
                                shouldHide = true;
                            }
                        }

                        // Lên lịch ẩn/hiện thực thể trên luồng an toàn của chính thực thể đó.
                        final boolean finalShouldHide = shouldHide;
                        getFoliaLib().getScheduler().runAtEntity(entity, (entityTask) -> {
                            if (finalShouldHide) {
                                player.hideEntity(this, entity);
                            } else {
                                player.showEntity(this, entity);
                            }
                        });
                    }
                });
            }
        }, 20L, entityCheckIntervalTicks);
    }

    public Set<UUID> getTeleportGracePeriod() {
        return teleportGracePeriod;
    }

    private void startSupervisorTask() {
        getFoliaLib().getScheduler().runTimer( (task) -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isWorldWhitelisted(player.getWorld().getName())) {
                    continue;
                }

                boolean isHidden = playerHiddenState.getOrDefault(player.getUniqueId(), false);
                if (player.getLocation().getY() < 16 && isHidden) {

                    if (!player.isOnGround() && player.getVelocity().getY() < -0.5) {
                        continue;
                    }

                    debugLog("SUPERVISOR: Detected " + player.getName() + " is underground but in a hidden state. Forcing reveal!");
                    forceRevealPlayerState(player);
                }
            }
        }, 10L, 10L);
    }

    private void forceRevealPlayerState(Player player) {
        if (!player.isOnline()) return;

        debugLog("FORCE REVEAL: Forcing visible state for " + player.getName());

        playerHiddenState.put(player.getUniqueId(), false);
        triggerSmartRefresh(player, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (to == null || to.getWorld() == null) return;

        teleportGracePeriod.add(player.getUniqueId());
        getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
            teleportGracePeriod.remove(player.getUniqueId());
            debugLog("Grace period ended for " + player.getName());
        }, 30L);

        cancelGradualRefresh(player);

        if (!isWorldWhitelisted(to.getWorld().getName())) {
            playerHiddenState.put(player.getUniqueId(), false);
            schedulePostTeleportRefresh(player, to, false, event.getCause());
            return;
        }

        double protectionY = getConfig().getDouble("antixray.protection-y-level", 31.0);
        boolean shouldBeHidden = isUndergroundProtectionEnabled() && to.getY() >= protectionY;

        playerHiddenState.put(player.getUniqueId(), shouldBeHidden);
        debugLog("Player " + player.getName() + " teleported. Final state decided: HIDDEN=" + shouldBeHidden);
        maybeHandleBurstMovement(player, event.getFrom(), to, true, event.getCause());
        schedulePostTeleportRefresh(player, to, shouldBeHidden, event.getCause());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        handlePlayerInitialState(event.getPlayer());
        teleportGracePeriod.add(event.getPlayer().getUniqueId());
        getFoliaLib().getScheduler().runAtEntityLater(event.getPlayer(), () -> {
            teleportGracePeriod.remove(event.getPlayer().getUniqueId());
        }, 30L);
    }

    public static TazAntixRAYPlugin getInstance() {
        return instance;
    }

    @Override
    public void onDisable() {
        if (packetEventsInitialized && PacketEvents.getAPI() != null) {
            try {
                PacketEvents.getAPI().terminate();
            } catch (Exception ex) {
                getLogger().warning("Failed to terminate PacketEvents cleanly: " + ex.getMessage());
            }
            packetEventsInitialized = false;
        }
        playerHiddenState.clear();
        teleportGracePeriod.clear();
        limitedAreaSmartRefreshTimestamps.clear();
        limitedAreaEmergencyCooldowns.clear();
        getFoliaLib().getScheduler().cancelAllTasks();
    }

    public void handlePlayerInitialState(Player player) {
        if (player == null) return;

        if (!isWorldWhitelisted(player.getWorld().getName())) {
            if (playerHiddenState.getOrDefault(player.getUniqueId(), false)) {
                forceRevealPlayerState(player);
            } else {
                playerHiddenState.remove(player.getUniqueId());
            }
            return;
        }
        double currentY = player.getLocation().getY();
        double protectionY = getConfig().getDouble("antixray.protection-y-level", 31.0);
        boolean shouldBeHidden = isUndergroundProtectionEnabled() && currentY >= protectionY;
        playerHiddenState.put(player.getUniqueId(), shouldBeHidden);
    }

    private void cancelGradualRefresh(Player player) {
        List<WrappedTask> oldTasks = gradualRefreshTasks.remove(player.getUniqueId());
        if (oldTasks != null) {
            oldTasks.forEach(WrappedTask::cancel);
        }
    }

    public void triggerSmartRefresh(Player player, boolean forceImmediate) {
        long delay = forceImmediate ? 1L : gradualRefreshDelayTicks;
        if (delay <= 0) delay = 1L;

        getFoliaLib().getScheduler().runAtEntityLater(player, (task) -> {
            if (!player.isOnline()) return;
            boolean isHidden = playerHiddenState.getOrDefault(player.getUniqueId(), false);
            if (isLimitedAreaEnabled() && !isHidden) {
                refreshLimitedAreaChunks(player);
                refreshChunksOutsideLimitedArea(player);
            } else {
                refreshViewGradually(player, null);
            }
        }, delay);
    }

    public void refreshViewGradually(Player player, List<Chunk> chunksToRefresh) {
        if (!player.isOnline()) return;

        cancelGradualRefresh(player);

        getFoliaLib().getImpl().runAtEntity(player, (task) -> {
            final List<Chunk> chunks;
            if (chunksToRefresh == null) {
                World world = player.getWorld();
                Location playerLoc = player.getLocation();
                int cx = playerLoc.getChunk().getX();
                int cz = playerLoc.getChunk().getZ();
                int viewDistance = player.getClientViewDistance();
                chunks = new ArrayList<>();
                for (int x = cx - viewDistance; x <= cx + viewDistance; x++) {
                    for (int z = cz - viewDistance; z <= cz + viewDistance; z++) {
                        if (world.isChunkLoaded(x, z)) {
                            chunks.add(world.getChunkAt(x, z));
                        }
                    }
                }
            } else {
                chunks = new ArrayList<>(chunksToRefresh);
            }
            if (chunks.isEmpty()) return;

            chunks.sort(Comparator.comparingDouble(c -> c.getBlock(8, 0, 8).getLocation().distanceSquared(player.getLocation())));

            int totalChunks = chunks.size();
            int chunksPerTickNow = Math.max(1, this.chunksPerTick);
            List<WrappedTask> newTasks = new ArrayList<>();
            gradualRefreshTasks.put(player.getUniqueId(), newTasks);
            for (int i = 0; i < totalChunks; i++) {
                final Chunk chunk = chunks.get(i);
                long delay = (long) i / chunksPerTickNow + 1;
                newTasks.add(getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                    if (player.isOnline()) player.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
                }, delay));
            }
        });
    }

    public void refreshLimitedAreaChunks(Player player) {
        if (!player.isOnline()) return;
        getFoliaLib().getImpl().runAtEntity(player, task -> {
            World world = player.getWorld();
            int playerChunkX = player.getLocation().getChunk().getX();
            int playerChunkZ = player.getLocation().getChunk().getZ();
            int radius = getLimitedAreaChunkRadius();
            for (int x = playerChunkX - radius; x <= playerChunkX + radius; x++) {
                for (int z = playerChunkZ - radius; z <= playerChunkZ + radius; z++) {
                    if (world.isChunkLoaded(x, z)) {
                        world.refreshChunk(x, z);
                    }
                }
            }
        });
    }

    private void updateLimitedAreaView(Player player, int oldChunkX, int oldChunkZ) {
        if (!player.isOnline()) return;

        long delay = limitedAreaUpdateDelayTicks;
        if (delay <= 0) delay = 1L;

        getFoliaLib().getImpl().runAtEntityLater(player, task -> {
            if (!player.isOnline()) return;

            World world = player.getWorld();
            int newChunkX = player.getLocation().getChunk().getX();
            int newChunkZ = player.getLocation().getChunk().getZ();
            if (oldChunkX == newChunkX && oldChunkZ == newChunkZ) return;

            int radius = getLimitedAreaChunkRadius();
            Set<Long> oldChunks = new HashSet<>();
            Set<Long> newChunks = new HashSet<>();
            List<Chunk> newChunksToRefresh = new ArrayList<>();
            List<Chunk> chunksLeavingBubble = new ArrayList<>();

            for (int x = oldChunkX - radius; x <= oldChunkX + radius; x++) {
                for (int z = oldChunkZ - radius; z <= oldChunkZ + radius; z++) {
                    oldChunks.add(Chunk.getChunkKey(x, z));
                }
            }
            for (int x = newChunkX - radius; x <= newChunkX + radius; x++) {
                for (int z = newChunkZ - radius; z <= newChunkZ + radius; z++) {
                    long key = Chunk.getChunkKey(x, z);
                    newChunks.add(key);
                    if (!oldChunks.contains(key)) {
                        if (world.isChunkLoaded(x, z)) {
                            newChunksToRefresh.add(world.getChunkAt(x, z));
                        }
                    }
                }
            }

            if (!oldChunks.isEmpty()) {
                for (long key : oldChunks) {
                    if (newChunks.contains(key)) continue;
                    int chunkX = chunkXFromKey(key);
                    int chunkZ = chunkZFromKey(key);
                    if (world.isChunkLoaded(chunkX, chunkZ)) {
                        chunksLeavingBubble.add(world.getChunkAt(chunkX, chunkZ));
                    }
                }
            }

            if (!newChunksToRefresh.isEmpty()) {
                debugLog("Limited-area fast refresh for " + player.getName() + ", refreshing " + newChunksToRefresh.size() + " new chunks.");
                for(Chunk chunk : newChunksToRefresh) {
                    world.refreshChunk(chunk.getX(), chunk.getZ());
                }
            }

            if (!chunksLeavingBubble.isEmpty()) {
                debugLog("Limited-area: re-obfuscating " + chunksLeavingBubble.size() + " chunks that left the bubble for " + player.getName() + ".");
                for (Chunk chunk : chunksLeavingBubble) {
                    world.refreshChunk(chunk.getX(), chunk.getZ());
                }
            }
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isWorldWhitelisted(player.getWorld().getName())) return;

        long currentTime = System.currentTimeMillis();
        boolean oldState = playerHiddenState.getOrDefault(player.getUniqueId(), false);
        double currentY = event.getTo().getY();
        double protectionY = getConfig().getDouble("antixray.protection-y-level", 31.0);
        boolean newState = isUndergroundProtectionEnabled() && currentY >= protectionY;

        if (oldState != newState) {
            debugLog("Player " + player.getName() + " crossed Y-threshold. New state: HIDDEN=" + newState);
            playerHiddenState.put(player.getUniqueId(), newState);
            refreshCooldowns.put(player.getUniqueId(), currentTime + refreshCooldownMillis);

            if (joinPacingEnabled && newState) {
                startPacedFullRefresh(player);
            } else {
                triggerSmartRefresh(player, !newState);
            }
            return;
        }

        if (!isLimitedAreaEnabled() || newState) {
            return;
        }

        maybeHandleBurstMovement(player, event.getFrom(), event.getTo(), false, null);

        int oldChunkX = event.getFrom().getChunk().getX();
        int oldChunkZ = event.getFrom().getChunk().getZ();
        int newChunkX = event.getTo().getChunk().getX();
        int newChunkZ = event.getTo().getChunk().getZ();

        if (oldChunkX != newChunkX || oldChunkZ != newChunkZ) {
            updateLimitedAreaView(player, oldChunkX, oldChunkZ);
            return;
        }

        if (shouldTriggerLimitedAreaSmartRefresh(player, event, currentTime)) {
            refreshLimitedAreaChunks(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerHiddenState.remove(uuid);
        refreshCooldowns.remove(uuid);
        teleportGracePeriod.remove(uuid);
        limitedAreaSmartRefreshTimestamps.remove(uuid);
        limitedAreaEmergencyCooldowns.remove(uuid);
        cancelGradualRefresh(event.getPlayer());
    }

    @Override
    public void onLoad() {
        PacketEventsAPI api = PacketEvents.getAPI();
        if (api != null) {
            if (!api.isLoaded()) {
                try {
                    api.load();
                } catch (Exception e) {
                    getLogger().severe("PacketEvents API detected but failed to load during onLoad.");
                    e.printStackTrace();
                    getServer().getPluginManager().disablePlugin(this);
                }
            }
            return;
        }

        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().getSettings().checkForUpdates(false).bStats(true);
            PacketEvents.getAPI().load();
        } catch (Exception e) {
            getLogger().severe("Failed to initialize PacketEvents during onLoad.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public void debugLog(String message) {
        if (debugMode) {
            getLogger().info("[TazAntixRAY DEBUG] " + message);
        }
    }

    public void triggerSmartRefresh(Player player) {
        triggerSmartRefresh(player, false);
    }

    private void startPacedFullRefresh(Player player) {
        long initialDelay = Math.max(1, joinPacingInitialDelay);

        getFoliaLib().getScheduler().runAtEntityLater(player, (mainTask) -> {
            if (!player.isOnline()) return;

            World world = player.getWorld();
            Location playerLoc = player.getLocation();
            int cx = playerLoc.getChunk().getX();
            int cz = playerLoc.getChunk().getZ();
            int viewDistance = player.getClientViewDistance();

            List<Chunk> chunksToRefresh = new ArrayList<>();
            for (int x = cx - viewDistance; x <= cx + viewDistance; x++) {
                for (int z = cz - viewDistance; z <= cz + viewDistance; z++) {
                    if (world.isChunkLoaded(x, z)) {
                        chunksToRefresh.add(world.getChunkAt(x, z));
                    }
                }
            }

            if (chunksToRefresh.isEmpty()) return;

            debugLog("PACED REFRESH: Starting slow refresh for " + player.getName() + " (" + chunksToRefresh.size() + " chunks)");

            chunksToRefresh.sort(Comparator.comparingDouble(c -> c.getBlock(8, 0, 8).getLocation().distanceSquared(player.getLocation())));

            cancelGradualRefresh(player);
            List<WrappedTask> newTasks = new ArrayList<>();
            gradualRefreshTasks.put(player.getUniqueId(), newTasks);

            int intervalTicks = Math.max(1, joinPacingIntervalTicks);
            int chunksPerInterval = Math.max(1, joinPacingChunksPerInterval);


            for (int i = 0; i < chunksToRefresh.size(); i += chunksPerInterval) {
                List<Chunk> chunkBatch = chunksToRefresh.subList(i, Math.min(i + chunksPerInterval, chunksToRefresh.size()));

                long delay = (long) (i / chunksPerInterval) * intervalTicks;

                newTasks.add(getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                    if (player.isOnline()) {
                        for (Chunk chunk : chunkBatch) {
                            player.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
                        }
                    }
                }, delay));
            }

        }, initialDelay);
    }

    // Sự kiện PlayerLoginEvent (ưu tiên THẤP NHẤT)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld(); // Lấy thế giới mà người chơi sẽ vào

        // Đặt trạng thái ẩn ngay lập tức KHI đăng nhập, TRƯỚC KHI bất kỳ chunk nào được gửi.
        if (isWorldWhitelisted(world.getName())) {
            // Lấy Y-level từ config
            double protectionY = getConfig().getDouble("antixray.protection-y-level", 31.0);
            // Lấy Y-level của điểm spawn (hoặc vị trí đăng nhập)
            double currentY = player.getLocation().getY();

            if (player.hasPlayedBefore()) {
                currentY = player.getLocation().getY();
            } else {
                currentY = world.getSpawnLocation().getY();
            }

            // Quyết định trạng thái ẩn
            boolean shouldBeHidden = isUndergroundProtectionEnabled() && currentY >= protectionY;
            playerHiddenState.put(player.getUniqueId(), shouldBeHidden);

            debugLog("Set initial hide state for " + player.getName() + " on LOGIN. Hidden: " + shouldBeHidden);
        }
    }


    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isWorldWhitelisted(player.getWorld().getName())) {

            // Logic `handlePlayerInitialState(player)` đã được chuyển lên onPlayerLogin

            getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                if (!player.isOnline()) return;

                // Tác vụ trễ này bây giờ chỉ chịu trách nhiệm làm mới (refresh)
                // để áp dụng 'limited-area' nếu người chơi ở dưới lòng đất,
                // hoặc để bắt đầu làm mới từ từ.
                if (joinPacingEnabled) {
                    startPacedFullRefresh(player);
                } else {
                    triggerSmartRefresh(player, true);
                }
            }, 20L); // Giữ delay 20L để refresh, không ảnh hưởng đến việc ẩn ban đầu.
        }
    }

    private void loadConfigValues() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();
        config.options().copyDefaults(true);

        loadLanguageConfig();
        whitelistedWorlds = new HashSet<>(config.getStringList("worlds.whitelist"));
        refreshCooldownMillis = config.getInt("settings.refresh-cooldown-seconds", 2) * 1000;
        debugMode = config.getBoolean("settings.debug-mode", false);
        replacementMode = config.getString("performance.replacement.mode", "DECEPTIVE").toUpperCase();
        limitedAreaEnabled = config.getBoolean("performance.limited-area.enabled", true);
        limitedAreaChunkRadius = config.getInt("performance.limited-area.chunk-radius", 3);
        limitedAreaSmartRefreshEnabled = config.getBoolean("performance.limited-area.smart-refresh.enabled", true);
        limitedAreaSmartRefreshMinDistance = config.getDouble("performance.limited-area.smart-refresh.min-move-distance", 2.5D);
        limitedAreaSmartRefreshMinRotation = (float) config.getDouble("performance.limited-area.smart-refresh.min-rotation-degrees", 25D);
        if (limitedAreaSmartRefreshMinDistance < 0.1D) {
            limitedAreaSmartRefreshMinDistance = 0.1D;
        }
        if (limitedAreaSmartRefreshMinRotation < 1F) {
            limitedAreaSmartRefreshMinRotation = 1F;
        }
        int smartRefreshCooldownTicks = config.getInt("performance.limited-area.smart-refresh.cooldown-ticks", 5);
        if (smartRefreshCooldownTicks < 1) smartRefreshCooldownTicks = 1;
        limitedAreaSmartRefreshCooldownMillis = smartRefreshCooldownTicks * 50;
        double emergencyDistance = config.getDouble("performance.limited-area.smart-refresh.emergency-distance", 8.0D);
        if (emergencyDistance < 0D) emergencyDistance = 0D;
        limitedAreaEmergencyDistanceSquared = emergencyDistance * emergencyDistance;
        int emergencyCooldownTicks = config.getInt("performance.limited-area.smart-refresh.emergency-cooldown-ticks", 4);
        if (emergencyCooldownTicks < 0) emergencyCooldownTicks = 0;
        limitedAreaEmergencyCooldownMillis = emergencyCooldownTicks * 50;
        limitedAreaSightlineLookaheadChunks = Math.max(0, config.getInt("performance.limited-area.smart-refresh.lookahead-chunks", 2));
        limitedAreaEnderpearlRevealEnabled = config.getBoolean("performance.limited-area.smart-refresh.reveal-on-enderpearl", true);
        undergroundProtectionEnabled = config.getBoolean("antixray.underground-protection.enabled", true);
        hideEntitiesEnabled = config.getBoolean("performance.entities.hide-entities", true);
        entityCheckIntervalTicks = config.getInt("performance.entities.check-interval-ticks", 10);

        gradualRefreshDelayTicks = config.getInt("performance.scheduling.gradual-refresh-delay-ticks", 5);
        chunksPerTick = config.getInt("performance.scheduling.chunks-per-tick", 25);
        limitedAreaUpdateDelayTicks = config.getInt("performance.scheduling.limited-area-update-delay-ticks", 2);

        joinPacingEnabled = config.getBoolean("performance.join-pacing.enabled", true);
        joinPacingInitialDelay = config.getInt("performance.join-pacing.initial-delay-ticks", 40);
        joinPacingChunksPerInterval = config.getInt("performance.join-pacing.chunks-per-interval", 2);
        joinPacingIntervalTicks = config.getInt("performance.join-pacing.interval-ticks", 4);

        saveConfig();
    }

    public String getMessage(String key, Object... args) {
        if (langConfig == null) {
            loadLanguageConfig();
        }
        String message = langConfig.getString("messages." + key, "Message for '" + key + "' not found.");
        String formattedMessage = message;
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                formattedMessage = formattedMessage.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }
        return ChatColor.translateAlternateColorCodes('&', formattedMessage);
    }

    public boolean isWorldWhitelisted(String worldName) {
        if (worldName == null) return false;
        return whitelistedWorlds.contains(worldName);
    }

    public boolean isUndergroundProtectionEnabled() {
        return undergroundProtectionEnabled;
    }

    private void schedulePacketEventsInitialization(PacketEventsAPI packetEventsAPI) {
        PlatformCompatibility.runTaskLater(this, () -> {
            if (!isEnabled()) {
                getLogger().warning("Plugin disabled before PacketEvents.init() could run.");
                return;
            }

            try {
                packetEventsAPI.init();
                packetEventsInitialized = true;
                initializeReplacementBlock();
                packetEventsAPI.getEventManager().registerListener(new ChunkPacketListenerPE(this), PacketListenerPriority.NORMAL);
                debugLog("PacketEvents initialized via delayed task.");
            } catch (IllegalPluginAccessException ex) {
                getLogger().warning("PacketEvents.init() was called too early; retrying next tick.");
                schedulePacketEventsInitialization(packetEventsAPI);
            } catch (Exception ex) {
                getLogger().severe("Failed to initialize PacketEvents! Disabling plugin.");
                ex.printStackTrace();
                getServer().getPluginManager().disablePlugin(this);
            }
        }, 1L);
    }

    private void initializeReplacementBlock() {
        try {
            this.replacementBlockState = AntiXrayUtils.getReplacementBlock(this, WrappedBlockState.getByString("minecraft:air"));
        } catch (Exception e) {
            getLogger().severe("Could not initialize replacement block state! Defaulting to air.");
            try {
                this.replacementBlockState = WrappedBlockState.getByString("minecraft:air");
            } catch (Exception ex) {
                getLogger().severe("FATAL: Could not even get air block state. Disabling plugin.");
                getServer().getPluginManager().disablePlugin(this);
            }
        }
    }

    private void registerCommands() {
        getCommand("tazantixray").setExecutor(this);
    }

    private void loadLanguageConfig() {
        String language = getConfig().getString("settings.language", "en");
        File langFile = new File(getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists()) {
            saveResource("lang/" + language + ".yml", false);
        }
        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }

    public String getReplacementMode() {
        return replacementMode;
    }

    public WrappedBlockState getReplacementBlockState() {
        return replacementBlockState;
    }

    public PaperOptimizer getPaperOptimizer() {
        return paperOptimizer;
    }

    public FoliaOptimizer getFoliaOptimizer() {
        return foliaOptimizer;
    }

    public boolean isLimitedAreaEnabled() {
        return limitedAreaEnabled;
    }

    public int getLimitedAreaChunkRadius() {
        return limitedAreaChunkRadius;
    }

    public GeyserFloodgateSupport getGeyserFloodgateSupport() {
        return geyserFloodgateSupport;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public boolean isHideEntitiesEnabled() {
        return hideEntitiesEnabled;
    }

    private void refreshChunksOutsideLimitedArea(Player player) {
        if (!player.isOnline()) return;

        getFoliaLib().getImpl().runAtEntity(player, task -> {
            World world = player.getWorld();
            Location location = player.getLocation();
            int centerChunkX = location.getChunk().getX();
            int centerChunkZ = location.getChunk().getZ();
            int viewDistance = player.getClientViewDistance();
            int bubbleRadius = getLimitedAreaChunkRadius();

            List<Chunk> chunksToRefresh = new ArrayList<>();
            for (int x = centerChunkX - viewDistance; x <= centerChunkX + viewDistance; x++) {
                for (int z = centerChunkZ - viewDistance; z <= centerChunkZ + viewDistance; z++) {
                    if (!world.isChunkLoaded(x, z)) continue;

                    boolean insideBubble = Math.abs(x - centerChunkX) <= bubbleRadius && Math.abs(z - centerChunkZ) <= bubbleRadius;
                    if (insideBubble) continue;

                    chunksToRefresh.add(world.getChunkAt(x, z));
                }
            }

            if (!chunksToRefresh.isEmpty()) {
                debugLog("Limited-area: refreshing " + chunksToRefresh.size() + " chunks outside the bubble for " + player.getName());
                refreshViewGradually(player, chunksToRefresh);
            }
        });
    }

    private void refreshChunksInstantly(Player player, int chunkRadius) {
        if (!player.isOnline()) return;
        int radius = Math.max(1, chunkRadius);
        getFoliaLib().getImpl().runAtEntity(player, task -> {
            World world = player.getWorld();
            Location location = player.getLocation();
            int centerChunkX = location.getChunk().getX();
            int centerChunkZ = location.getChunk().getZ();

            for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
                for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
                    if (world.isChunkLoaded(x, z)) {
                        world.refreshChunk(x, z);
                    }
                }
            }
        });
    }

    private void requestImmediateFullObfuscation(Player player) {
        if (!player.isOnline()) return;
        refreshChunksInstantly(player, Math.max(1, player.getClientViewDistance()));
        if (joinPacingEnabled) {
            startPacedFullRefresh(player);
        } else {
            refreshViewGradually(player, null);
        }
    }

    private void schedulePostTeleportRefresh(Player player, Location destination, boolean isNowHidden, PlayerTeleportEvent.TeleportCause cause) {
        if (destination == null) {
            return;
        }

        getFoliaLib().getScheduler().runAtLocationLater(destination, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (destination.getWorld() == null || !isWorldWhitelisted(destination.getWorld().getName())) {
                refreshViewGradually(player, null);
                return;
            }

            if (isNowHidden) {
                requestImmediateFullObfuscation(player);
                return;
            }

            if (isLimitedAreaEnabled()) {
                boolean includeSightline = limitedAreaEnderpearlRevealEnabled
                        && cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
                requestLimitedAreaEmergencyReveal(player, includeSightline, "teleport");
                refreshChunksOutsideLimitedArea(player);
            } else {
                refreshViewGradually(player, null);
            }
        }, 1L);
    }

    private void maybeHandleBurstMovement(Player player, Location from, Location to, boolean teleport, PlayerTeleportEvent.TeleportCause cause) {
        if (!isLimitedAreaEnabled()) {
            return;
        }
        if (playerHiddenState.getOrDefault(player.getUniqueId(), false)) {
            return;
        }
        if (from == null || to == null) {
            return;
        }
        if (!teleport && limitedAreaEmergencyDistanceSquared <= 0D) {
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;

        if (!teleport && distanceSquared < limitedAreaEmergencyDistanceSquared) {
            return;
        }

        boolean includeSightline = teleport
                && limitedAreaEnderpearlRevealEnabled
                && cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
        requestLimitedAreaEmergencyReveal(player, includeSightline, teleport ? "teleport" : "movement");
    }

    private void requestLimitedAreaEmergencyReveal(Player player, boolean includeSightline, String reason) {
        if (!isLimitedAreaEnabled()) {
            return;
        }
        if (playerHiddenState.getOrDefault(player.getUniqueId(), false)) {
            return;
        }

        long now = System.currentTimeMillis();
        long nextAllowed = limitedAreaEmergencyCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < nextAllowed) {
            if (includeSightline) {
                revealChunksAlongSightline(player);
            }
            return;
        }

        limitedAreaEmergencyCooldowns.put(player.getUniqueId(), now + Math.max(0, limitedAreaEmergencyCooldownMillis));
        debugLog("Limited-area emergency reveal triggered for " + player.getName() + " due to " + reason + ".");

        getFoliaLib().getImpl().runAtEntity(player, task -> {
            if (!player.isOnline()) {
                return;
            }
            refreshLimitedAreaChunks(player);
            if (includeSightline) {
                revealChunksAlongSightline(player);
            }
        });
    }

    private void revealChunksAlongSightline(Player player) {
        if (limitedAreaSightlineLookaheadChunks <= 0) {
            return;
        }

        getFoliaLib().getImpl().runAtEntity(player, task -> {
            if (!player.isOnline()) {
                return;
            }

            Location eye = player.getEyeLocation();
            Vector direction = eye.getDirection();
            if (direction == null || direction.lengthSquared() < 1.0E-4) {
                return;
            }
            direction.normalize();

            World world = player.getWorld();
            int bubbleRadius = getLimitedAreaChunkRadius();
            double maxDistance = Math.max(4D, (bubbleRadius + limitedAreaSightlineLookaheadChunks) * 16D);
            Set<Long> chunkKeys = new HashSet<>();

            for (double distance = 2D; distance <= maxDistance; distance += 4D) {
                double offsetX = direction.getX() * distance;
                double offsetZ = direction.getZ() * distance;
                int blockX = eye.getBlockX() + (int) Math.round(offsetX);
                int blockZ = eye.getBlockZ() + (int) Math.round(offsetZ);
                int chunkX = blockX >> 4;
                int chunkZ = blockZ >> 4;

                if (!AntiXrayUtils.isChunkInLimitedArea(player, chunkX, chunkZ, bubbleRadius)) {
                    continue;
                }

                long key = Chunk.getChunkKey(chunkX, chunkZ);
                if (!chunkKeys.add(key)) {
                    continue;
                }

                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    world.refreshChunk(chunkX, chunkZ);
                }
            }
        });
    }

    private int chunkXFromKey(long key) {
        return (int) (key & 0xffffffffL);
    }

    private int chunkZFromKey(long key) {
        return (int) ((key >>> 32) & 0xffffffffL);
    }

    private boolean shouldTriggerLimitedAreaSmartRefresh(Player player, PlayerMoveEvent event, long currentTimeMillis) {
        if (!limitedAreaSmartRefreshEnabled) {
            return false;
        }

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dy = event.getTo().getY() - event.getFrom().getY();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;

        float yawDelta = computeAngleDelta(event.getFrom().getYaw(), event.getTo().getYaw());
        float pitchDelta = Math.abs(event.getTo().getPitch() - event.getFrom().getPitch());

        double minDistanceSquared = limitedAreaSmartRefreshMinDistance * limitedAreaSmartRefreshMinDistance;
        if (distanceSquared < minDistanceSquared
                && yawDelta < limitedAreaSmartRefreshMinRotation
                && pitchDelta < limitedAreaSmartRefreshMinRotation) {
            return false;
        }

        long nextAllowed = limitedAreaSmartRefreshTimestamps.getOrDefault(player.getUniqueId(), 0L);
        if (currentTimeMillis < nextAllowed) {
            return false;
        }

        limitedAreaSmartRefreshTimestamps.put(player.getUniqueId(), currentTimeMillis + limitedAreaSmartRefreshCooldownMillis);
        return true;
    }

    private float computeAngleDelta(float from, float to) {
        float delta = Math.abs(to - from) % 360F;
        if (delta > 180F) {
            delta = 360F - delta;
        }
        return delta;
    }
}