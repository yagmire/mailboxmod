package me.yag;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// im aware this code is some buns, i made it in like 6 hours...

public class Mailboxmod implements ModInitializer {
    private final Map<java.util.UUID, Long> cooldowns = new HashMap<>();
    private final Map<String, Boolean> pendingMail = new HashMap<>();
    private int repairTickCounter = 0;

    public static final SavedDataType<MailboxData> MAILBOX_TYPE =
            new SavedDataType<>(
                    Identifier.fromNamespaceAndPath("mailboxmod", "mailbox_data"),
                    MailboxData::new,
                    MailboxData.CODEC,
                    DataFixTypes.LEVEL
            );

    private static MailboxData getMailboxData(MinecraftServer server) {
        return server.getLevel(Level.OVERWORLD).getDataStorage().computeIfAbsent(MAILBOX_TYPE);
    }

    @Override
    public void onInitialize() {

        ServerTickEvents.END_LEVEL_TICK.register(world -> {
            for (ServerPlayer player : world.players()) {
                if (pendingMail.getOrDefault(player.getScoreboardName(), false)) {
                    player.sendSystemMessage(Component.literal("You have mail! Check your mailbox.").withStyle(ChatFormatting.GOLD), false);
                    player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
                    pendingMail.put(player.getScoreboardName(), false);
                }
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            ServerPlayer serverPlayer = (ServerPlayer) player;
            ServerLevel serverWorld = (ServerLevel) world;
            BlockPos clickedPos = hitResult.getBlockPos();
            ItemStack stack = player.getItemInHand(hand);

            MailboxData data = getMailboxData(serverWorld.getServer());

            if (world.getBlockState(clickedPos).getBlock() == Blocks.BARREL) {
                String owner = data.getOwnerAt(clickedPos);
                boolean onFence = world.getBlockState(clickedPos.below()).is(BlockTags.FENCES);

                if (owner != null && onFence) {
                    if (!owner.equals(serverPlayer.getScoreboardName())) {
                        serverPlayer.sendSystemMessage(Component.literal("This mailbox belongs to " + owner + "!").withStyle(ChatFormatting.RED), true);
                        return InteractionResult.FAIL;
                    } else {
                        updateMailboxLabel(serverWorld, clickedPos, owner, false);
                    }
                }
            }

            if (stack.getItem() == Items.BARREL) {
                BlockPos placePos = clickedPos.relative(hitResult.getDirection());

                boolean onFence = world.getBlockState(placePos.below()).is(BlockTags.FENCES);
                boolean isAir = world.getBlockState(placePos).isAir();

                if (onFence && isAir) {
                    if (data.hasMailbox(serverPlayer.getScoreboardName())) {
                        serverPlayer.sendSystemMessage(Component.literal("You already have a mailbox!").withStyle(ChatFormatting.RED), true);
                        return InteractionResult.FAIL;
                    }

                    Direction playerFacing = player.getDirection().getOpposite();
                    world.setBlock(placePos, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, playerFacing), 3);

                    updateMailboxLabel(serverWorld, placePos, serverPlayer.getScoreboardName(), false);

                    if (!serverPlayer.isCreative()) stack.shrink(1);
                    data.addMailbox(serverPlayer.getScoreboardName(), placePos);
                    serverPlayer.sendSystemMessage(Component.literal("Mailbox created!").withStyle(ChatFormatting.GREEN), false);
                    return InteractionResult.SUCCESS;
                }
            }

            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerLevel serverWorld)) return true;
            if (!(player instanceof ServerPlayer serverPlayer)) return true;

            if (state.getBlock() == Blocks.BARREL) {
                MailboxData data = getMailboxData(serverWorld.getServer());
                String owner = data.getOwnerAt(pos);
                boolean onFence = world.getBlockState(pos.below()).is(BlockTags.FENCES);

                if (owner != null && onFence) {
                    boolean isAdmin = Commands.LEVEL_GAMEMASTERS.check(serverPlayer.permissions());
                    boolean isOwner = owner.equals(serverPlayer.getScoreboardName());

                    if (isOwner || isAdmin) {
                        removeMailboxLabel(serverWorld, pos);
                        data.removeMailbox(owner);
                        serverPlayer.sendSystemMessage(Component.literal("Mailbox removed.").withStyle(ChatFormatting.YELLOW), false);
                        return true;
                    } else {
                        serverPlayer.sendSystemMessage(Component.literal("You cannot break " + owner + "'s mailbox!").withStyle(ChatFormatting.RED), true);
                        return false;
                    }
                }
            }
            return true;
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            MailboxData data = getMailboxData(server);

            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, BlockPos> entry : data.getAllMailboxes().entrySet()) {
                BlockPos pos = entry.getValue();
                if (overworld.getBlockState(pos).getBlock() == Blocks.BARREL) {
                    ensureLabelExists(overworld, pos, entry.getKey());
                } else {
                    removeMailboxLabel(overworld, pos);
                    toRemove.add(entry.getKey());
                }
            }
            for (String owner : toRemove) data.removeMailbox(owner);

            if (++repairTickCounter >= 600) {
                repairTickCounter = 0;
                repairOrphanedLabels(overworld, data);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("mailTo")
                    .then(Commands.argument("username", StringArgumentType.word())
                            .executes(context -> mailToCommand(context))));

            dispatcher.register(
                    Commands.literal("mailbox")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("deleteNearest")
                                    .executes(ctx -> deleteNearestMailbox(ctx.getSource())))
                            .then(Commands.literal("inspect")
                                    .then(Commands.argument("owner", StringArgumentType.word())
                                            .executes(ctx -> inspectMailbox(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "owner")))))
                            .then(Commands.literal("list")
                                    .executes(ctx -> listMailboxes(ctx.getSource())))
                            .then(Commands.literal("tp")
                                    .then(Commands.argument("owner", StringArgumentType.word())
                                            .executes(ctx -> teleportToMailbox(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "owner")))))
            );
        });
    }

    private int inspectMailbox(CommandSourceStack source, String owner) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);
        MailboxData data = getMailboxData(source.getServer());
        BlockPos mailboxPos = data.getMailbox(owner);

        if (mailboxPos == null || !(overworld.getBlockState(mailboxPos).getBlock() instanceof BarrelBlock)) {
            player.sendSystemMessage(Component.literal(owner + " has no mailbox!").withStyle(ChatFormatting.RED));
            return 0;
        }

        Container inv = (Container) overworld.getBlockEntity(mailboxPos);
        player.sendSystemMessage(Component.literal(owner + "'s Mailbox Contents:").withStyle(ChatFormatting.YELLOW));

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                player.sendSystemMessage(Component.literal("- " + stack.getCount() + "x " + stack.getHoverName().getString()));
            }
        }

        return 1;
    }

    private int listMailboxes(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        MailboxData data = getMailboxData(source.getServer());
        Map<String, BlockPos> all = data.getAllMailboxes();

        if (all.isEmpty()) {
            player.sendSystemMessage(Component.literal("No mailboxes found.").withStyle(ChatFormatting.RED));
            return 0;
        }

        player.sendSystemMessage(Component.literal("Server Mailboxes:").withStyle(ChatFormatting.YELLOW));
        for (Map.Entry<String, BlockPos> entry : all.entrySet()) {
            player.sendSystemMessage(Component.literal("- " + entry.getKey() + " at " + entry.getValue()));
        }

        return 1;
    }

    private int teleportToMailbox(CommandSourceStack source, String owner) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        MailboxData data = getMailboxData(source.getServer());
        BlockPos pos = data.getMailbox(owner);

        if (pos == null) {
            player.sendSystemMessage(Component.literal(owner + " has no mailbox!").withStyle(ChatFormatting.RED));
            return 0;
        }

        player.teleportTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        player.sendSystemMessage(Component.literal("Teleported to " + owner + "'s mailbox.").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private int deleteNearestMailbox(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);
        MailboxData data = getMailboxData(source.getServer());

        BlockPos playerPos = player.blockPosition();

        BlockPos closestPos = null;
        String closestOwner = null;
        double closestDistanceSq = Double.MAX_VALUE;

        for (Map.Entry<String, BlockPos> entry : data.getAllMailboxes().entrySet()) {
            BlockPos mailboxPos = entry.getValue();

            if (overworld.getBlockState(mailboxPos).getBlock() != Blocks.BARREL) continue;

            double distSq = mailboxPos.distSqr(playerPos);

            if (distSq <= 25 && distSq < closestDistanceSq) {
                closestDistanceSq = distSq;
                closestPos = mailboxPos;
                closestOwner = entry.getKey();
            }
        }

        if (closestPos == null) {
            player.sendSystemMessage(
                    Component.literal("No mailbox found within 5 blocks.")
                            .withStyle(ChatFormatting.RED)
            );
            return 0;
        }

        overworld.setBlock(closestPos, Blocks.AIR.defaultBlockState(), 3);
        removeMailboxLabel(overworld, closestPos);
        data.removeMailbox(closestOwner);

        player.sendSystemMessage(
                Component.literal("Removed mailbox owned by ")
                        .append(Component.literal(closestOwner).withStyle(ChatFormatting.GOLD))
                        .withStyle(ChatFormatting.YELLOW)
        );

        return 1;
    }

    private void updateMailboxLabel(ServerLevel world, BlockPos pos, String owner, boolean hasMail) {
        removeMailboxLabel(world, pos);
        ArmorStand label = new ArmorStand(EntityType.ARMOR_STAND, world);
        label.setInvisible(true);
        label.setInvulnerable(true);
        label.setNoGravity(true);
        label.setCustomNameVisible(true);

        String text = owner + "'s Mailbox";
        if (hasMail) {
            text = "§6● §f" + owner + "'s Mailbox §6● §7(New Mail!)";
        }

        label.setCustomName(Component.literal(text));
        label.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        world.addFreshEntity(label);
    }

    private void removeMailboxLabel(ServerLevel world, BlockPos pos) {
        List<ArmorStand> entities = world.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(0.1), e -> true);
        for (ArmorStand stand : entities) stand.discard();
    }

    private void ensureLabelExists(ServerLevel world, BlockPos pos, String owner) {
        List<ArmorStand> entities = world.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(0.1), e -> true);
        if (entities.isEmpty()) updateMailboxLabel(world, pos, owner, false);
    }

    private void repairOrphanedLabels(ServerLevel world, MailboxData data) {
        Map<BlockPos, String> registeredByPos = new HashMap<>();
        for (Map.Entry<String, BlockPos> entry : data.getAllMailboxes().entrySet()) {
            registeredByPos.put(entry.getValue(), entry.getKey());
        }

        AABB searchBounds = new AABB(-30_000_000, -320, -30_000_000, 30_000_000, 320, 30_000_000);
        List<ArmorStand> stands = world.getEntitiesOfClass(ArmorStand.class, searchBounds,
                e -> e.getCustomName() != null && e.getCustomName().getString().contains("'s Mailbox"));

        for (ArmorStand stand : stands) {
            BlockPos pos = stand.blockPosition();
            boolean barrelPresent = world.getBlockState(pos).getBlock() == Blocks.BARREL;
            boolean onFence = world.getBlockState(pos.below()).is(BlockTags.FENCES);

            if (!barrelPresent || !onFence) {
                stand.discard();
                continue;
            }

            if (!registeredByPos.containsKey(pos)) {
                String owner = extractOwnerFromLabel(stand.getCustomName().getString());
                if (owner != null && !owner.isEmpty() && !data.hasMailbox(owner)) {
                    data.addMailbox(owner, pos);
                    registeredByPos.put(pos, owner);
                } else {
                    stand.discard();
                }
            }
        }
    }

    private String extractOwnerFromLabel(String label) {
        String clean = label.replaceAll("§.", "").trim();
        if (clean.startsWith("● ")) clean = clean.substring(2).trim();
        int idx = clean.indexOf("'s Mailbox");
        return idx > 0 ? clean.substring(0, idx) : null;
    }

    private int mailToCommand(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayer();
        if (sender == null) return 0;

        long currentTime = System.currentTimeMillis();
        if (currentTime - cooldowns.getOrDefault(sender.getUUID(), 0L) < 20000) {
            sender.sendSystemMessage(Component.literal("Wait before sending more mail!").withStyle(ChatFormatting.RED), true);
            return 0;
        }

        String targetUsername = StringArgumentType.getString(context, "username");
        if (sender.getScoreboardName().equalsIgnoreCase(targetUsername)) {
            sender.sendSystemMessage(Component.literal("You cannot send mail to yourself!").withStyle(ChatFormatting.RED), true);
            return 0;
        }

        MinecraftServer server = context.getSource().getServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        ItemStack handStack = sender.getMainHandItem();
        if (handStack.isEmpty()) {
            sender.sendSystemMessage(Component.literal("You must hold an item to mail!").withStyle(ChatFormatting.RED), true);
            return 0;
        }

        MailboxData data = getMailboxData(server);
        BlockPos mailboxPos = data.getMailbox(targetUsername);

        if (mailboxPos == null || !(overworld.getBlockState(mailboxPos).getBlock() instanceof BarrelBlock)) {
            sender.sendSystemMessage(Component.literal("Target has no mailbox!").withStyle(ChatFormatting.RED));
            return 0;
        }

        Container inventory = (Container) overworld.getBlockEntity(mailboxPos);
        ItemStack stackToSend = handStack.copy();

        sender.playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);

        ItemLore lore = stackToSend.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        java.util.List<Component> lines = new java.util.ArrayList<>();
        for (Component t : lore.lines()) if (!t.getString().startsWith("From: ")) lines.add(t);
        lines.add(Component.literal("From: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(sender.getScoreboardName()).withStyle(ChatFormatting.GOLD))
                .withStyle(s -> s.withItalic(false)));
        stackToSend.set(DataComponents.LORE, new ItemLore(lines));

        ItemStack sentStack = stackToSend.copy();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (stackToSend.isEmpty()) break;
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) {
                inventory.setItem(i, stackToSend);
                stackToSend = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameComponents(slot, stackToSend)) {
                int maxSize = slot.getOrDefault(DataComponents.MAX_STACK_SIZE, 64);
                int count = Math.min(stackToSend.getCount(), maxSize - slot.getCount());
                slot.grow(count);
                stackToSend.shrink(count);
            }
        }

        if (!sentStack.isEmpty() && sentStack.getCount() != stackToSend.getCount()) {
            updateMailboxLabel(overworld, mailboxPos, targetUsername, true);
        }

        int sentCount = sentStack.getCount() - stackToSend.getCount();
        if (sentCount > 0) {
            handStack.shrink(sentCount);
        }

        if (!stackToSend.isEmpty()) {
            if (!sender.getInventory().add(stackToSend)) {
                sender.drop(stackToSend, false);
            }
        }

        inventory.setChanged();
        cooldowns.put(sender.getUUID(), currentTime);

        if (sentCount > 0) {
            sender.sendSystemMessage(Component.literal("Sent " + sentCount + "x " + sentStack.getHoverName().getString() + " to " + targetUsername + "!").withStyle(ChatFormatting.GREEN));
        } else {
            sender.sendSystemMessage(Component.literal("Could not send any items to " + targetUsername + "!").withStyle(ChatFormatting.RED));
        }

        ServerPlayer recipient = server.getPlayerList().getPlayerByName(targetUsername);
        if (recipient != null) {
            recipient.sendSystemMessage(Component.literal("You received mail from " + sender.getScoreboardName() + "!").withStyle(ChatFormatting.AQUA));
            recipient.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
        } else {
            pendingMail.put(targetUsername, true);
        }

        return 1;
    }
}
