package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.BlockPosition;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSectionBlocksUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.BlockChangeRecord;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSectionBlocksUpdate.SectionBlockChangeEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ChunkPacketListenerPE implements PacketListener {
    private final TazAntixRAYPlugin plugin;

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
            case SECTION_BLOCKS_UPDATE -> handleSectionBlockUpdate(event, player, isPlayerInHidingState);
            default -> {
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
        BlockPosition position = blockChange.getBlockPosition();
        if (position == null) return;

        if (!shouldObfuscateBlock(player, isPlayerInHidingState, position.getX(), position.getY(), position.getZ())) {
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
        BlockChangeRecord[] records = multiBlockChange.getBlockChangeRecords();
        if (records == null || records.length == 0) {
            return;
        }

        boolean modified = false;
        WrappedBlockState replacement = plugin.getReplacementBlockState();
        if (replacement == null) {
            return;
        }

        for (BlockChangeRecord record : records) {
            if (record == null) continue;
            BlockPosition position = record.getBlockPosition();
            if (position == null) continue;

            if (shouldObfuscateBlock(player, isPlayerInHidingState, position.getX(), position.getY(), position.getZ())) {
                record.setBlockState(replacement);
                modified = true;
            }
        }

        if (modified) {
            multiBlockChange.setBlockChangeRecords(records);
            event.markForReEncode(true);
        }
    }

    private void handleSectionBlockUpdate(PacketSendEvent event, Player player, boolean isPlayerInHidingState) {
        WrapperPlayServerSectionBlocksUpdate sectionBlocksUpdate = new WrapperPlayServerSectionBlocksUpdate(event);
        SectionBlockChangeEntry[] entries = sectionBlocksUpdate.getBlockChanges();
        if (entries == null || entries.length == 0) {
            return;
        }

        boolean modified = false;
        WrappedBlockState replacement = plugin.getReplacementBlockState();
        if (replacement == null) {
            return;
        }

        for (SectionBlockChangeEntry entry : entries) {
            if (entry == null) continue;
            BlockPosition position = entry.getBlockPosition();
            if (position == null) continue;

            if (shouldObfuscateBlock(player, isPlayerInHidingState, position.getX(), position.getY(), position.getZ())) {
                entry.setBlockState(replacement);
                modified = true;
            }
        }

        if (modified) {
            sectionBlocksUpdate.setBlockChanges(entries);
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
}