package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.bukkit.World;
import java.util.HashSet;
import java.util.Set;

public class FoliaOptimizer { // Hoặc PaperOptimizer
    private final TazAntixRAYPlugin plugin;
    private WrappedBlockState REPLACEMENT_BLOCK;
    private WrappedBlockState BEDROCK_BLOCK;
    private final Set<Integer> airLikeIds = new HashSet<>();
    private int bedrockGlobalId = -1; // Biến cache cho ID của bedrock

    public FoliaOptimizer(TazAntixRAYPlugin plugin) {
        this.plugin = plugin;
        initializeBlockStates();
    }

    private void initializeBlockStates() {
        // Sử dụng getReplacementBlock từ AntiXrayUtils để đảm bảo block thay thế luôn hợp lệ
        this.REPLACEMENT_BLOCK = AntiXrayUtils.getReplacementBlock(plugin, WrappedBlockState.getByString("minecraft:air"));
        try {
            this.BEDROCK_BLOCK = WrappedBlockState.getByString("minecraft:bedrock");
            this.bedrockGlobalId = BEDROCK_BLOCK.getGlobalId(); // Cache ID của bedrock

            airLikeIds.add(WrappedBlockState.getByString("minecraft:air").getGlobalId());
            airLikeIds.add(WrappedBlockState.getByString("minecraft:cave_air").getGlobalId());
            airLikeIds.add(WrappedBlockState.getByString("minecraft:void_air").getGlobalId());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize essential block states for Optimizer!");
            e.printStackTrace();
        }
    }

    public boolean handleAdvancedObfuscation(Column column, World world, boolean isPlayerAboveGround) {
        if (REPLACEMENT_BLOCK == null) {
            this.REPLACEMENT_BLOCK = AntiXrayUtils.getReplacementBlock(plugin, WrappedBlockState.getByString("minecraft:air"));
            if (REPLACEMENT_BLOCK == null) return false;
        }
        // Đảm bảo bedrock ID đã được cache
        if (this.bedrockGlobalId == -1) {
            try {
                this.bedrockGlobalId = WrappedBlockState.getByString("minecraft:bedrock").getGlobalId();
            } catch (Exception e) {
                plugin.getLogger().warning("Could not cache bedrock ID!");
            }
        }


        BaseChunk[] chunkSections = column.getChunks();
        if (chunkSections == null) return false;

        boolean modified = false;
        int hideBelowY = plugin.getConfig().getInt("antixray.hide-below-y", 16);
        int worldMinY = world.getMinHeight();

        boolean isAggressiveMode = isPlayerAboveGround || plugin.getReplacementMode().equals("DECEPTIVE");

        for (int i = 0; i < chunkSections.length; i++) {
            BaseChunk section = chunkSections[i];
            if (section == null) continue;

            int sectionMinY = worldMinY + (i * 16);
            if (sectionMinY > hideBelowY) continue;

            for (int y = 0; y < 16; y++) {
                int currentY = sectionMinY + y;
                if (currentY > hideBelowY) continue;

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {

                        if (!isAggressiveMode) {
                            int globalId = section.get(x, y, z).getGlobalId();
                            if (airLikeIds.contains(globalId)) {
                                continue;
                            }
                        }

                        if (currentY <= worldMinY + 4 && currentY >= worldMinY) {
                            int globalId = section.get(x, y, z).getGlobalId();
                            if (globalId == this.bedrockGlobalId) {
                                continue;
                            }
                        }

                        modified = true;
                        section.set(x, y, z, REPLACEMENT_BLOCK);
                    }
                }
            }
        }

        // [ĐÃ XÓA] Logic setTileEntities(new TileEntity[0]) đã bị xóa khỏi đây vì nó GÂY LỖI.

        return modified;
    }

    public void shutdown() { }
}