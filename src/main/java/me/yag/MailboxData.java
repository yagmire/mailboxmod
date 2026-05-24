package me.yag;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

public class MailboxData extends SavedData {

    private final Map<String, BlockPos> mailboxes = new HashMap<>();

    public static final Codec<MailboxData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, BlockPos.CODEC)
                    .fieldOf("mailboxes")
                    .forGetter(data -> data.mailboxes)
    ).apply(instance, map -> {
        MailboxData data = new MailboxData();
        data.mailboxes.putAll(map);
        return data;
    }));

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
        setDirty();
    }

    public void removeMailbox(String username) {
        mailboxes.remove(username);
        setDirty();
    }

    public BlockPos getMailbox(String username) {
        return mailboxes.get(username);
    }

    public Map<String, BlockPos> getAllMailboxes() {
        return mailboxes;
    }
}
