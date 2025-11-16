package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ChunkPacketListenerPE implements PacketListener {
    private final TazAntixRAYPlugin plugin;

    private static final Constructor<?> SECTION_BLOCKS_UPDATE_CONSTRUCTOR;
    private static final Method SECTION_BLOCKS_UPDATE_GET_CHANGES_METHOD;
    private static final Method SECTION_BLOCKS_UPDATE_SET_CHANGES_METHOD;
    private static final PacketType.Play.Server SECTION_BLOCKS_UPDATE_PACKET_TYPE;

    static {
        Constructor<?> constructor = null;
        Method getChanges = null;
        Method setChanges = null;
        try {
            Class<?> wrapperClass = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSectionBlocksUpdate");
            constructor = wrapperClass.getConstructor(PacketSendEvent.class);
            getChanges = wrapperClass.getMethod("getBlockChanges");
            for (Method method : wrapperClass.getMethods()) {
                if (method.getName().equals("setBlockChanges")) {
                    setChanges = method;
                    break;
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // PacketEvents build without WrapperPlayServerSectionBlocksUpdate support.
        }
        SECTION_BLOCKS_UPDATE_CONSTRUCTOR = constructor;
        SECTION_BLOCKS_UPDATE_GET_CHANGES_METHOD = getChanges;
        SECTION_BLOCKS_UPDATE_SET_CHANGES_METHOD = setChanges;
        SECTION_BLOCKS_UPDATE_PACKET_TYPE = resolvePacketType("SECTION_BLOCKS_UPDATE");
    }

    private static PacketType.Play.Server resolvePacketType(String fieldName) {
        try {
            return (PacketType.Play.Server) PacketType.Play.Server.class.getField(fieldName).get(null);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    public ChunkPacketListenerPE(TazAntixRAYPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;

        Player player = Bukkit.getPlayer(user.getUUID());
        if (player == null || !player.isOnline() || !plugin.isWorldWhitelisted(player.getWorld().getName())) {
            return;
        }

        boolean isPlayerInHidingState = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);
        PacketType.Play.Server packetType = (PacketType.Play.Server) event.getPacketType();
        switch (packetType) {
            case CHUNK_DATA -> handleChunkData(event, player, isPlayerInHidingState);
            case BLOCK_CHANGE -> handleSingleBlockChange(event, player, isPlayerInHidingState);
            case MULTI_BLOCK_CHANGE -> handleMultiBlockChange(event, player, isPlayerInHidingState);
            default -> {
                if (SECTION_BLOCKS_UPDATE_PACKET_TYPE != null && packetType == SECTION_BLOCKS_UPDATE_PACKET_TYPE) {
                    handleSectionBlockUpdate(event, player, isPlayerInHidingState);
                }
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Not used
    }

    private void handleChunkData(PacketSendEvent event, Player player, boolean isPlayerInHidingState) {
        WrapperPlayServerChunkData chunkDataWrapper = new WrapperPlayServerChunkData(event);
        Column column = chunkDataWrapper.getColumn();
        if (column == null) return;

        boolean shouldObfuscate;

        if (isPlayerInHidingState) {
            shouldObfuscate = true;
        } else if (plugin.isLimitedAreaEnabled()) {
            boolean isChunkInRadius = AntiXrayUtils.isChunkInLimitedArea(
                    player,
                    column.getX(),
                    column.getZ(),
                    plugin.getLimitedAreaChunkRadius()
            );
            shouldObfuscate = !isChunkInRadius;
        } else {
            shouldObfuscate = false;
        }

        if (!shouldObfuscate) {
            return;
        }

        boolean modified = false;
        if (plugin.getFoliaOptimizer() != null) {
            modified = plugin.getFoliaOptimizer().handleAdvancedObfuscation(
                    column,
                    player.getWorld(),
                    isPlayerInHidingState
            );
        } else if (plugin.getPaperOptimizer() != null) {
            modified = plugin.getPaperOptimizer().handleAdvancedObfuscation(
                    column,
                    player.getWorld(),
                    isPlayerInHidingState
            );
        }

        if (!modified) {
            return;
        }

        TileEntity[] emptyTileEntities = new TileEntity[0];
        Column newColumn;

        boolean hasHeightmaps = column.hasHeightMaps();
        boolean hasBiomeData = column.hasBiomeData();

        NBTCompound heightmapsNbt = null;
        if (hasHeightmaps) {
            heightmapsNbt = column.getHeightMaps();
        }

        int[] biomeInts = null;
        byte[] biomeBytes = null;
        if (hasBiomeData) {
            biomeInts = column.getBiomeDataInts();
            if (biomeInts == null || biomeInts.length == 0) {
                biomeBytes = column.getBiomeDataBytes();
            }
        }

        if (hasHeightmaps && hasBiomeData) {
            if (biomeInts != null && biomeInts.length > 0) {
                newColumn = new Column(
                        column.getX(),
                        column.getZ(),
                        column.isFullChunk(),
                        column.getChunks(),
                        emptyTileEntities,
                        heightmapsNbt,
                        biomeInts
                );
            } else if (biomeBytes != null && biomeBytes.length > 0) {
                newColumn = new Column(
                        column.getX(),
                        column.getZ(),
                        column.isFullChunk(),
                        column.getChunks(),
                        emptyTileEntities,
                        heightmapsNbt,
                        biomeBytes
                );
            } else {
                newColumn = new Column(
                        column.getX(),
                        column.getZ(),
                        column.isFullChunk(),
                        column.getChunks(),
                        emptyTileEntities,
                        heightmapsNbt
                );
            }
        } else if (hasHeightmaps) {
            newColumn = new Column(
                    column.getX(),
                    column.getZ(),
                    column.isFullChunk(),
                    column.getChunks(),
                    emptyTileEntities,
                    heightmapsNbt
            );
        } else if (hasBiomeData) {
            if (biomeInts != null && biomeInts.length > 0) {
                newColumn = new Column(
                        column.getX(),
                        column.getZ(),
                        column.isFullChunk(),
                        column.getChunks(),
                        emptyTileEntities,
                        biomeInts
                );
            } else if (biomeBytes != null && biomeBytes.length > 0) {
                newColumn = new Column(
                        column.getX(),
                        column.getZ(),
                        column.isFullChunk(),
                        column.getChunks(),
                        emptyTileEntities,
                        biomeBytes
                );
            } else {
                newColumn = new Column(
                        column.getX(),
                        column.getZ(),
                        column.isFullChunk(),
                        column.getChunks(),
                        emptyTileEntities
                );
            }
        } else {
            newColumn = new Column(
                    column.getX(),
                    column.getZ(),
                    column.isFullChunk(),
                    column.getChunks(),
                    emptyTileEntities
            );
        }

        chunkDataWrapper.setColumn(newColumn);
        event.markForReEncode(true);
    }

    private void handleSingleBlockChange(PacketSendEvent event, Player player, boolean isPlayerInHidingState) {
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(event);
        Object position = blockChange.getBlockPosition();
        int[] coordinates = extractBlockCoordinates(position);
        if (coordinates == null) {
            return;
        }

        if (!shouldObfuscateBlock(player, isPlayerInHidingState, coordinates[0], coordinates[1], coordinates[2])) {
            return;
        }

        WrappedBlockState replacement = plugin.getReplacementBlockState();
        if (replacement != null) {
            blockChange.setBlockState(replacement);
            event.markForReEncode(true);
        }
    }

    private void handleMultiBlockChange(PacketSendEvent event, Player player, boolean isPlayerInHidingState) {
        WrapperPlayServerMultiBlockChange multiBlockChange = new WrapperPlayServerMultiBlockChange(event);
        Object recordsRaw = getMultiBlockChangeRecords(multiBlockChange);
        if (recordsRaw == null || !recordsRaw.getClass().isArray()) {
            return;
        }

        int length = Array.getLength(recordsRaw);
        if (length == 0) {
            return;
        }

        boolean modified = false;
        WrappedBlockState replacement = plugin.getReplacementBlockState();
        if (replacement == null) {
            return;
        }

        for (int i = 0; i < length; i++) {
            Object record = Array.get(recordsRaw, i);
            if (record == null) {
                continue;
            }

            Object position = extractBlockPosition(record);
            int[] coordinates = extractBlockCoordinates(position);
            if (coordinates == null) {
                continue;
            }

            if (shouldObfuscateBlock(player, isPlayerInHidingState, coordinates[0], coordinates[1], coordinates[2]) &&
                    trySetBlockState(record, replacement)) {
                modified = true;
            }
        }

        if (modified) {
            event.markForReEncode(true);
        }
    }

    private void handleSectionBlockUpdate(PacketSendEvent event, Player player, boolean isPlayerInHidingState) {
        Object sectionBlocksUpdate = instantiateSectionBlocksWrapper(event);
        if (sectionBlocksUpdate == null || SECTION_BLOCKS_UPDATE_GET_CHANGES_METHOD == null) {
            return;
        }

        Object entriesRaw = invokeSectionBlocksGetChanges(sectionBlocksUpdate);
        if (entriesRaw == null || !entriesRaw.getClass().isArray()) {
            return;
        }

        int length = Array.getLength(entriesRaw);
        if (length == 0) {
            return;
        }

        boolean modified = false;
        WrappedBlockState replacement = plugin.getReplacementBlockState();
        if (replacement == null) {
            return;
        }

        for (int i = 0; i < length; i++) {
            Object entry = Array.get(entriesRaw, i);
            if (entry == null) {
                continue;
            }

            Object position = extractBlockPosition(entry);
            int[] coordinates = extractBlockCoordinates(position);
            if (coordinates == null) {
                continue;
            }

            if (shouldObfuscateBlock(player, isPlayerInHidingState, coordinates[0], coordinates[1], coordinates[2]) &&
                    trySetBlockState(entry, replacement)) {
                modified = true;
            }
        }

        if (modified) {
            invokeSectionBlocksSetChanges(sectionBlocksUpdate, entriesRaw);
            event.markForReEncode(true);
        }
    }

    private boolean shouldObfuscateBlock(Player player, boolean isPlayerInHidingState, int blockX, int blockY, int blockZ) {
        int hideBelowY = plugin.getConfig().getInt("antixray.hide-below-y", 16);
        if (blockY > hideBelowY) {
            return false;
        }

        if (isPlayerInHidingState) {
            return true;
        }

        if (!plugin.isLimitedAreaEnabled()) {
            return false;
        }

        return !AntiXrayUtils.isBlockInLimitedArea(player, blockX, blockZ, plugin.getLimitedAreaChunkRadius());
    }

    private Object extractBlockPosition(Object holder) {
        if (holder == null) {
            return null;
        }

        try {
            Method method = holder.getClass().getMethod("getBlockPosition");
            return method.invoke(holder);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private int[] extractBlockCoordinates(Object blockPosition) {
        if (blockPosition == null) {
            return null;
        }

        Integer x = extractCoordinate(blockPosition, new String[]{"getX", "getBlockX", "x"});
        Integer y = extractCoordinate(blockPosition, new String[]{"getY", "getBlockY", "y"});
        Integer z = extractCoordinate(blockPosition, new String[]{"getZ", "getBlockZ", "z"});

        if (x == null || y == null || z == null) {
            return null;
        }

        return new int[]{x, y, z};
    }

    private Object getMultiBlockChangeRecords(WrapperPlayServerMultiBlockChange multiBlockChange) {
        if (multiBlockChange == null) {
            return null;
        }

        for (String methodName : new String[]{"getBlockChangeRecords", "getRecords", "getBlockChanges"}) {
            try {
                Method method = multiBlockChange.getClass().getMethod(methodName);
                return method.invoke(multiBlockChange);
            } catch (NoSuchMethodException ignored) {
                // Try the next method name.
            } catch (IllegalAccessException | InvocationTargetException ex) {
                plugin.debugLog("Failed to read multi-block change records via " + methodName + ": " + ex.getMessage());
                return null;
            }
        }

        return null;
    }

    private Integer extractCoordinate(Object target, String[] methodCandidates) {
        for (String methodName : methodCandidates) {
            try {
                Method method = target.getClass().getMethod(methodName);
                Object value = method.invoke(target);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next candidate.
            } catch (IllegalAccessException | InvocationTargetException ex) {
                plugin.debugLog("Failed to read coordinate via " + methodName + ": " + ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private boolean trySetBlockState(Object target, WrappedBlockState replacement) {
        if (target == null || replacement == null) {
            return false;
        }

        try {
            Method method = target.getClass().getMethod("setBlockState", WrappedBlockState.class);
            method.invoke(target, replacement);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.debugLog("Failed to set block state via reflection: " + ex.getMessage());
            return false;
        }
    }

    private Object instantiateSectionBlocksWrapper(PacketSendEvent event) {
        if (SECTION_BLOCKS_UPDATE_CONSTRUCTOR == null) {
            return null;
        }

        try {
            return SECTION_BLOCKS_UPDATE_CONSTRUCTOR.newInstance(event);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            plugin.debugLog("Unable to create WrapperPlayServerSectionBlocksUpdate: " + ex.getMessage());
            return null;
        }
    }

    private Object invokeSectionBlocksGetChanges(Object wrapper) {
        if (wrapper == null || SECTION_BLOCKS_UPDATE_GET_CHANGES_METHOD == null) {
            return null;
        }

        try {
            return SECTION_BLOCKS_UPDATE_GET_CHANGES_METHOD.invoke(wrapper);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.debugLog("Failed to read section block changes: " + ex.getMessage());
            return null;
        }
    }

    private void invokeSectionBlocksSetChanges(Object wrapper, Object entries) {
        if (wrapper == null || entries == null || SECTION_BLOCKS_UPDATE_SET_CHANGES_METHOD == null) {
            return;
        }

        try {
            SECTION_BLOCKS_UPDATE_SET_CHANGES_METHOD.invoke(wrapper, entries);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            plugin.debugLog("Failed to write section block changes: " + ex.getMessage());
        }
    }
}