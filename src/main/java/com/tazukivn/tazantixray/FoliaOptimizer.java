package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.bukkit.World;

/**
 * Handles low-level chunk obfuscation for Folia / Luminol.
 *
 * Goal (user requirement):
 *  - "Lấp sạch" mọi block dưới Y được cấu hình (antixray.hide-below-y)
 *    để không còn thấy nước, lava, amethyst bud, spawner... khi load chunk
 *    mới hoặc khi dịch chuyển / random teleport.
 *
 * Implementation:
 *  - Chỉ làm việc trên dữ liệu Column (PacketEvents), không đụng tới world thực.
 *  - Với mọi block có toạ độ Y <= hide-below-y:
 *      + Nếu là bedrock ở vài layer thấp nhất thì giữ nguyên.
 *      + Ngược lại: thay bằng REPLACEMENT_BLOCK (deepslate/stone/air tuỳ config).
 *  - Tuyệt đối không thay block nào ở Y > hide-below-y để tránh nhìn thấy
 *    deepslate "mọc" lên mặt đất.
 */
public class FoliaOptimizer {

    private final TazAntixRAYPlugin plugin;
    private WrappedBlockState replacementBlock;
    private int bedrockId = -1;

    public FoliaOptimizer(TazAntixRAYPlugin plugin) {
        this.plugin = plugin;
        initializeBlockStates();
    }

    private void initializeBlockStates() {
        // Block dùng để "lấp" – lấy từ config (minecraft:deepslate / stone / air...)
        this.replacementBlock = AntiXrayUtils.getReplacementBlock(
                plugin,
                WrappedBlockState.getByString("minecraft:air")
        );

        try {
            WrappedBlockState bedrock = WrappedBlockState.getByString("minecraft:bedrock");
            if (bedrock != null) {
                this.bedrockId = bedrock.getGlobalId();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[FoliaOptimizer] Failed to resolve bedrock block state.");
        }
    }

    /**
     * Thực hiện obfuscation cho một Column chunk.
     *
     * @param column              Dữ liệu chunk gửi tới client.
     * @param world               World tương ứng (chỉ dùng để lấy min/max height).
     * @param isPlayerAboveGround Có thể được dùng cho chiến lược khác, nhưng trong
     *                            bản này không ảnh hưởng: mọi block dưới Y đều bị lấp.
     * @return true nếu có chỉnh sửa dữ liệu chunk.
     */
    public boolean handleAdvancedObfuscation(Column column, World world, boolean isPlayerAboveGround) {
        if (column == null || world == null) return false;

        if (replacementBlock == null) {
            // Thử khởi tạo lại 1 lần nếu trước đó fail.
            initializeBlockStates();
            if (replacementBlock == null) {
                plugin.getLogger().warning("[FoliaOptimizer] Replacement block is null, skipping obfuscation.");
                return false;
            }
        }

        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) return false;

        final int hideBelowY = plugin.getConfig().getInt("antixray.hide-below-y", 16);
        final int worldMinY = world.getMinHeight();
        final int worldMaxY = world.getMaxHeight();

        // Nếu cấu hình nằm hoàn toàn dưới worldMinY thì thôi.
        if (hideBelowY < worldMinY) {
            return false;
        }

        boolean modified = false;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            BaseChunk section = sections[sectionIndex];
            if (section == null) continue;

            int sectionMinY = worldMinY + sectionIndex * 16;

            for (int y = 0; y < 16; y++) {
                int currentY = sectionMinY + y;

                if (currentY < worldMinY || currentY >= worldMaxY) {
                    // Ngoài range thế giới – PacketEvents đôi khi vẫn có section placeholder.
                    continue;
                }

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        WrappedBlockState state = section.get(x, y, z);
                        if (state == null) {
                            continue;
                        }

                        boolean sensitive = AntiXrayUtils.isSensitiveBlockState(state);
                        boolean allowSensitive = plugin.getConfig().getBoolean(
                                "antixray.sensitive-blocks.enabled",
                                false
                        );
                        // Optional path to also hide "sensitive" blocks when the player is in a
                        // hiding/limited-area state; never expands beyond configured conditions.

                        boolean baseRange = currentY <= hideBelowY;
                        boolean sensitiveRange = allowSensitive && sensitive
                                && (currentY <= hideBelowY
                                || isPlayerAboveGround
                                || plugin.isLimitedAreaEnabled());
                        boolean inTargetRange = baseRange || sensitiveRange;
                        if (!inTargetRange) {
                            continue;
                        }

                        int id = state.getGlobalId();

                        // Giữ nguyên bedrock ở vài layer thấp nhất để world không bị "thủng đáy".
                        if (bedrockId != -1
                                && id == bedrockId
                                && currentY <= worldMinY + 4
                                && currentY >= worldMinY) {
                            continue;
                        }

                        // Thay mọi block còn lại bằng replacementBlock (kể cả nước, lava, ore, amethyst...)
                        if (state != replacementBlock) {
                            section.set(x, y, z, replacementBlock);
                            modified = true;
                        }
                    }
                }
            }
        }

        return modified;
    }

    public void shutdown() {
        // Hiện không có tài nguyên nào cần giải phóng.
    }
}
