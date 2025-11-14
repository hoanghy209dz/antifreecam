package com.tazukivn.tazantixray;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;

public class EntityPacketListener implements PacketListener {

    private final TazAntixRAYPlugin plugin;

    public EntityPacketListener(TazAntixRAYPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // TÍNH NĂNG NÀY ĐÃ ĐƯỢC THAY THẾ BỞI MỘT TÁC VỤ (TASK) ỔN ĐỊNH HƠN TRONG LỚP PLUGIN CHÍNH
        // SỬ DỤNG BUKKIT API (player.hideEntity) THAY VÌ CAN THIỆP PACKET
        return;
    }
}