package me.yag;

import com.mojang.serialization.Codec;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;

public class MailboxData extends PersistentState {

    private final Map<String, BlockPos> mailboxes = new HashMap<>();

    // Correct Codec
    public static final Codec<MailboxData> CODEC = Codec.unboundedMap(Codec.STRING, BlockPos.CODEC)
            .xmap(map -> {
                MailboxData data = new MailboxData();
                data.mailboxes.putAll(map);
                return data;
            }, data -> data.mailboxes);

    public MailboxData() {
        super();
    }

    public boolean hasMailbox(String owner) {
        return mailboxes.containsKey(owner);
    }

    public String getOwnerAt(BlockPos pos) {
        for (Map.Entry<String, BlockPos> entry : this.mailboxes.entrySet()) {
            if (entry.getValue().equals(pos)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void addMailbox(String username, BlockPos pos) {
        mailboxes.put(username, pos);
        markDirty();
    }

    public void removeMailbox(String username) {
        mailboxes.remove(username);
        markDirty();
    }

    public BlockPos getMailbox(String username) {
        return mailboxes.get(username);
    }

    public Map<String, BlockPos> getAllMailboxes() {
        return mailboxes;
    }
}
