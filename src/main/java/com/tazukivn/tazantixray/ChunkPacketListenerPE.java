package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ChunkPacketListenerPE implements PacketListener {
    private final TazAntixRAYPlugin plugin;

    public ChunkPacketListenerPE(TazAntixRAYPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }

        User user = event.getUser();
        if (user == null) return;

        Player player = Bukkit.getPlayer(user.getUUID());
        if (player == null || !player.isOnline() || !plugin.isWorldWhitelisted(player.getWorld().getName())) {
            return;
        }

        boolean isPlayerInHidingState = plugin.playerHiddenState.getOrDefault(player.getUniqueId(), false);

        boolean shouldObfuscate;

        if (isPlayerInHidingState) {
            // Nếu người chơi ở trên cao, luôn che giấu
            shouldObfuscate = true;
        } else {
            // Nếu người chơi ở dưới lòng đất, chỉ che giấu khi bật limited-area VÀ chunk nằm ngoài bán kính
            if (plugin.isLimitedAreaEnabled()) {
                WrapperPlayServerChunkData chunkDataWrapper = new WrapperPlayServerChunkData(event);
                Column column = chunkDataWrapper.getColumn();
                if (column != null) {
                    boolean isChunkInRadius = AntiXrayUtils.isChunkInLimitedArea(
                            player,
                            column.getX(),
                            column.getZ(),
                            plugin.getLimitedAreaChunkRadius()
                    );
                    // Che giấu nếu KHÔNG nằm trong bán kính
                    shouldObfuscate = !isChunkInRadius;
                } else {
                    shouldObfuscate = false;
                }
            } else {
                shouldObfuscate = false;
            }
        }

        if (!shouldObfuscate) {
            return;
        }

        WrapperPlayServerChunkData chunkDataWrapper = new WrapperPlayServerChunkData(event);
        Column original = chunkDataWrapper.getColumn();
        if (original == null) return;

        boolean modified = false;
        // Truyền trạng thái của người chơi vào optimizer
        if (plugin.getFoliaOptimizer() != null) {
            modified = plugin.getFoliaOptimizer().handleAdvancedObfuscation(
                    original,
                    player.getWorld(),
                    isPlayerInHidingState
            );
        } else if (plugin.getPaperOptimizer() != null) {
            modified = plugin.getPaperOptimizer().handleAdvancedObfuscation(
                    original,
                    player.getWorld(),
                    isPlayerInHidingState
            );
        }

        if (modified) {
            // [SỬA LỖI SPAWNER] – API mới của PacketEvents (2.10.1+) không còn setTileEntities(...)
            // Column giữ tileEntities là final, nên muốn clear tile entities phải tạo Column mới
            // nhưng vẫn giữ nguyên heightmaps + biome data để tránh lỗi hiển thị chunk.

            TileEntity[] emptyTileEntities = new TileEntity[0];
            Column newColumn;

            boolean hasHeightmaps = original.hasHeightMaps();
            boolean hasBiomeData = original.hasBiomeData();

            NBTCompound heightmapsNbt = null;
            if (hasHeightmaps) {
                heightmapsNbt = original.getHeightMaps();
            }

            int[] biomeInts = null;
            byte[] biomeBytes = null;
            if (hasBiomeData) {
                biomeInts = original.getBiomeDataInts();
                if (biomeInts == null || biomeInts.length == 0) {
                    biomeBytes = original.getBiomeDataBytes();
                }
            }

            if (hasHeightmaps && hasBiomeData) {
                if (biomeInts != null && biomeInts.length > 0) {
                    newColumn = new Column(
                            original.getX(),
                            original.getZ(),
                            original.isFullChunk(),
                            original.getChunks(),
                            emptyTileEntities,
                            heightmapsNbt,
                            biomeInts
                    );
                } else if (biomeBytes != null && biomeBytes.length > 0) {
                    newColumn = new Column(
                            original.getX(),
                            original.getZ(),
                            original.isFullChunk(),
                            original.getChunks(),
                            emptyTileEntities,
                            heightmapsNbt,
                            biomeBytes
                    );
                } else {
                    newColumn = new Column(
                            original.getX(),
                            original.getZ(),
                            original.isFullChunk(),
                            original.getChunks(),
                            emptyTileEntities,
                            heightmapsNbt
                    );
                }
            } else if (hasHeightmaps) {
                newColumn = new Column(
                        original.getX(),
                        original.getZ(),
                        original.isFullChunk(),
                        original.getChunks(),
                        emptyTileEntities,
                        heightmapsNbt
                );
            } else if (hasBiomeData) {
                if (biomeInts != null && biomeInts.length > 0) {
                    newColumn = new Column(
                            original.getX(),
                            original.getZ(),
                            original.isFullChunk(),
                            original.getChunks(),
                            emptyTileEntities,
                            biomeInts
                    );
                } else if (biomeBytes != null && biomeBytes.length > 0) {
                    newColumn = new Column(
                            original.getX(),
                            original.getZ(),
                            original.isFullChunk(),
                            original.getChunks(),
                            emptyTileEntities,
                            biomeBytes
                    );
                } else {
                    newColumn = new Column(
                            original.getX(),
                            original.getZ(),
                            original.isFullChunk(),
                            original.getChunks(),
                            emptyTileEntities
                    );
                }
            } else {
                newColumn = new Column(
                        original.getX(),
                        original.getZ(),
                        original.isFullChunk(),
                        original.getChunks(),
                        emptyTileEntities
                );
            }

            // Gắn lại Column mới (đã clear TileEntities) vào wrapper
            chunkDataWrapper.setColumn(newColumn);

            // Yêu cầu PacketEvents encode lại packet với dữ liệu mới
            event.markForReEncode(true);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Not used
    }
}
