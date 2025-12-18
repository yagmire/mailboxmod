package me.yag;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Blocks;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentStateType;
import net.minecraft.sound.SoundEvents;
import java.util.List;
import java.util.Map;

// im aware this code is some buns, i made it in like 6 hours...

public class Mailboxmod implements ModInitializer {
    private final java.util.Map<java.util.UUID, Long> cooldowns = new java.util.HashMap<>();
    private final Map<String, Boolean> pendingMail = new java.util.HashMap<>();

    public static final PersistentStateType<MailboxData> MAILBOX_TYPE =
            new PersistentStateType<>(
                    "mailbox_data",
                    context -> new MailboxData(),
                    context -> MailboxData.CODEC,
                    DataFixTypes.LEVEL
            );

    @Override
    public void onInitialize() {

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (pendingMail.getOrDefault(player.getName().getString(), false)) {
                    player.sendMessage(Text.literal("You have mail! Check your mailbox.").formatted(Formatting.GOLD), false);
                    player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
                    pendingMail.put(player.getName().getString(), false);
                }
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
            ServerWorld serverWorld = (ServerWorld) world;
            BlockPos clickedPos = hitResult.getBlockPos();
            ItemStack stack = player.getStackInHand(hand);

            MailboxData data = serverWorld.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);

            if (world.getBlockState(clickedPos).getBlock() == Blocks.BARREL) {
                String owner = data.getOwnerAt(clickedPos);
                boolean onFence = world.getBlockState(clickedPos.down()).isIn(BlockTags.FENCES);

                if (owner != null && onFence) {
                    if (!owner.equals(serverPlayer.getName().getString())) {
                        serverPlayer.sendMessage(Text.literal("This mailbox belongs to " + owner + "!").formatted(Formatting.RED), true);
                        return ActionResult.FAIL;
                    } else {
                        updateMailboxLabel(serverWorld, clickedPos, owner, false);
                    }
                }
            }


            if (stack.getItem() == Items.BARREL) {
                BlockPos placePos = clickedPos.offset(hitResult.getSide());

                boolean onFence = world.getBlockState(placePos.down()).isIn(BlockTags.FENCES);
                boolean isAir = world.getBlockState(placePos).isAir();

                if (onFence && isAir) {
                    if (data.hasMailbox(serverPlayer.getName().getString())) {
                        serverPlayer.sendMessage(Text.literal("You already have a mailbox!").formatted(Formatting.RED), true);
                        return ActionResult.FAIL;
                    }

                    Direction playerFacing = player.getHorizontalFacing().getOpposite();
                    world.setBlockState(placePos, Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, playerFacing), 3);

                    updateMailboxLabel(serverWorld, placePos, serverPlayer.getName().getString(), false);

                    if (!serverPlayer.isCreative()) stack.decrement(1);
                    data.addMailbox(serverPlayer.getName().getString(), placePos);
                    serverPlayer.sendMessage(Text.literal("Mailbox created!").formatted(Formatting.GREEN), false);
                    return ActionResult.SUCCESS;
                }
            }


            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerWorld serverWorld)) return true;

            if (state.getBlock() == Blocks.BARREL) {
                MailboxData data = serverWorld.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);
                String owner = data.getOwnerAt(pos);
                boolean onFence = world.getBlockState(pos.down()).isIn(BlockTags.FENCES);

                if (owner != null && onFence) {
                    boolean isAdmin = player.hasPermissionLevel(2);
                    boolean isOwner = owner.equals(player.getName().getString());

                    if (isOwner || isAdmin) {
                        removeMailboxLabel(serverWorld, pos);
                        data.removeMailbox(owner);
                        player.sendMessage(Text.literal("Mailbox removed.").formatted(Formatting.YELLOW), false);
                        return true;
                    } else {
                        player.sendMessage(Text.literal("You cannot break " + owner + "'s mailbox!").formatted(Formatting.RED), true);
                        return false;
                    }
                }
            }
            return true;
        });


        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerWorld world : server.getWorlds()) {
                MailboxData data = world.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);
                for (Map.Entry<String, BlockPos> entry : data.getAllMailboxes().entrySet()) {
                    if (world.getBlockState(entry.getValue()).getBlock() == Blocks.BARREL) {
                        ensureLabelExists(world, entry.getValue(), entry.getKey());
                    }
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("mailTo")
                    .then(CommandManager.argument("username", StringArgumentType.word())
                            .executes(context -> mailToCommand(context))));

            dispatcher.register(
                    CommandManager.literal("mailbox")
                            .requires(source -> source.hasPermissionLevel(2))
                            .then(CommandManager.literal("deleteNearest")
                                    .executes(ctx -> deleteNearestMailbox(ctx.getSource())))
                            .then(CommandManager.literal("inspect")
                                    .then(CommandManager.argument("owner", StringArgumentType.word())
                                            .executes(ctx -> inspectMailbox(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "owner")))))
                            .then(CommandManager.literal("list")
                                    .executes(ctx -> listMailboxes(ctx.getSource())))
                            .then(CommandManager.literal("tp")
                                    .then(CommandManager.argument("owner", StringArgumentType.word())
                                            .executes(ctx -> teleportToMailbox(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "owner")))))
            );
        });

    }

    private int inspectMailbox(ServerCommandSource source, String owner) {
        ServerPlayerEntity player;
        try { player = source.getPlayer(); } catch (Exception e) { return 0; }

        ServerWorld world = player.getEntityWorld();
        MailboxData data = world.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);
        BlockPos mailboxPos = data.getMailbox(owner);

        if (mailboxPos == null || !(world.getBlockState(mailboxPos).getBlock() instanceof BarrelBlock)) {
            player.sendMessage(Text.literal(owner + " has no mailbox!").formatted(Formatting.RED), false);
            return 0;
        }

        Inventory inv = (Inventory) world.getBlockEntity(mailboxPos);
        player.sendMessage(Text.literal(owner + "'s Mailbox Contents:").formatted(Formatting.YELLOW), false);

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                player.sendMessage(Text.literal("- " + stack.getCount() + "x " + stack.getName().getString()), false);
            }
        }

        return 1;
    }

    private int listMailboxes(ServerCommandSource source) {
        ServerPlayerEntity player;
        try { player = source.getPlayer(); } catch (Exception e) { return 0; }

        ServerWorld world = player.getEntityWorld();
        MailboxData data = world.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);
        Map<String, BlockPos> all = data.getAllMailboxes();

        if (all.isEmpty()) {
            player.sendMessage(Text.literal("No mailboxes found.").formatted(Formatting.RED), false);
            return 0;
        }

        player.sendMessage(Text.literal("Server Mailboxes:").formatted(Formatting.YELLOW), false);
        for (Map.Entry<String, BlockPos> entry : all.entrySet()) {
            player.sendMessage(Text.literal("- " + entry.getKey() + " at " + entry.getValue()), false);
        }

        return 1;
    }

    private int teleportToMailbox(ServerCommandSource source, String owner) {
        ServerPlayerEntity player;
        try { player = source.getPlayer(); } catch (Exception e) { return 0; }

        ServerWorld world = player.getEntityWorld();
        MailboxData data = world.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);
        BlockPos pos = data.getMailbox(owner);

        if (pos == null) {
            player.sendMessage(Text.literal(owner + " has no mailbox!").formatted(Formatting.RED), false);
            return 0;
        }

        player.requestTeleportAndDismount(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        player.sendMessage(Text.literal("Teleported to " + owner + "'s mailbox.").formatted(Formatting.GREEN), false);
        return 1;
    }


    private int deleteNearestMailbox(ServerCommandSource source) {
        ServerPlayerEntity player;
        try {
            player = source.getPlayer();
        } catch (Exception e) {
            return 0;
        }

        ServerWorld world = player.getEntityWorld();
        MailboxData data = world.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);

        BlockPos playerPos = player.getBlockPos();

        BlockPos closestPos = null;
        String closestOwner = null;
        double closestDistanceSq = Double.MAX_VALUE;

        for (Map.Entry<String, BlockPos> entry : data.getAllMailboxes().entrySet()) {
            BlockPos mailboxPos = entry.getValue();

            if (world.getBlockState(mailboxPos).getBlock() != Blocks.BARREL) continue;

            double distSq = mailboxPos.getSquaredDistance(playerPos);

            if (distSq <= 25 && distSq < closestDistanceSq) {
                closestDistanceSq = distSq;
                closestPos = mailboxPos;
                closestOwner = entry.getKey();
            }
        }

        if (closestPos == null) {
            player.sendMessage(
                    Text.literal("No mailbox found within 5 blocks.")
                            .formatted(Formatting.RED),
                    false
            );
            return 0;
        }

        world.setBlockState(closestPos, Blocks.AIR.getDefaultState(), 3);

        removeMailboxLabel(world, closestPos);
        data.removeMailbox(closestOwner);

        player.sendMessage(
                Text.literal("Removed mailbox owned by ")
                        .append(Text.literal(closestOwner).formatted(Formatting.GOLD))
                        .formatted(Formatting.YELLOW),
                false
        );

        return 1;
    }


    private void updateMailboxLabel(ServerWorld world, BlockPos pos, String owner, boolean hasMail) {
        removeMailboxLabel(world, pos);
        ArmorStandEntity label = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        label.setInvisible(true);
        label.setInvulnerable(true);
        label.setNoGravity(true);
        label.setCustomNameVisible(true);

        String text = owner + "'s Mailbox";
        if (hasMail) {
            text = "§6● §f" + owner + "'s Mailbox §6● §7(New Mail!)";
        }

        label.setCustomName(Text.literal(text));
        label.updatePosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        world.spawnEntity(label);
    }

    private void removeMailboxLabel(ServerWorld world, BlockPos pos) {
        List<ArmorStandEntity> entities = world.getEntitiesByClass(ArmorStandEntity.class, new Box(pos).expand(0.1), e -> true);
        for (ArmorStandEntity stand : entities) stand.remove(Entity.RemovalReason.DISCARDED);
    }


    private void ensureLabelExists(ServerWorld world, BlockPos pos, String owner) {
        List<ArmorStandEntity> entities = world.getEntitiesByClass(ArmorStandEntity.class, new Box(pos).expand(0.1), e -> true);
        if (entities.isEmpty()) updateMailboxLabel(world, pos, owner, false);
    }

    private int mailToCommand(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity sender = context.getSource().getPlayer();
        if (sender == null) return 0;

        long currentTime = System.currentTimeMillis();
        if (currentTime - cooldowns.getOrDefault(sender.getUuid(), 0L) < 20000) {
            sender.sendMessage(Text.literal("Wait before sending more mail!").formatted(Formatting.RED), true);
            return 0;
        }

        String targetUsername = StringArgumentType.getString(context, "username");
        if (sender.getName().getString().equalsIgnoreCase(targetUsername)) {
            sender.sendMessage(Text.literal("You cannot send mail to yourself!").formatted(Formatting.RED), true);
            return 0;
        }

        ServerWorld world = sender.getEntityWorld();
        ItemStack handStack = sender.getMainHandStack();
        if (handStack.isEmpty()) {
            sender.sendMessage(Text.literal("You must hold an item to mail!").formatted(Formatting.RED), true);
            return 0;
        }

        MailboxData data = world.getPersistentStateManager().getOrCreate(MAILBOX_TYPE);
        BlockPos mailboxPos = data.getMailbox(targetUsername);

        if (mailboxPos == null || !(world.getBlockState(mailboxPos).getBlock() instanceof BarrelBlock)) {
            sender.sendMessage(Text.literal("Target has no mailbox! If the player's mailbox is in another dimension, you must be in that dimension to send them mail.").formatted(Formatting.RED), false);
            return 0;
        }

        Inventory inventory = (Inventory) world.getBlockEntity(mailboxPos);
        ItemStack stackToSend = handStack.copy();

        sender.playSound(
                SoundEvents.ENTITY_PLAYER_LEVELUP,
                1.0f, // volume
                1.0f  // pitch
        );

        net.minecraft.component.type.LoreComponent lore = stackToSend.getOrDefault(net.minecraft.component.DataComponentTypes.LORE, net.minecraft.component.type.LoreComponent.DEFAULT);
        java.util.List<Text> lines = new java.util.ArrayList<>();
        for (Text t : lore.lines()) if (!t.getString().startsWith("From: ")) lines.add(t);
        lines.add(Text.literal("From: ").formatted(Formatting.GRAY)
                .append(Text.literal(sender.getName().getString()).formatted(Formatting.GOLD))
                .styled(s -> s.withItalic(false)));
        stackToSend.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lines));

        ItemStack sentStack = stackToSend.copy();

        for (int i = 0; i < inventory.size(); i++) {
            if (stackToSend.isEmpty()) break;
            ItemStack slot = inventory.getStack(i);
            if (slot.isEmpty()) {
                inventory.setStack(i, stackToSend);
                stackToSend = ItemStack.EMPTY;
            } else if (ItemStack.areItemsAndComponentsEqual(slot, stackToSend)) {
                int count = Math.min(stackToSend.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(count);
                stackToSend.decrement(count);
            }
        }

        if (!sentStack.isEmpty() && sentStack.getCount() != stackToSend.getCount()) {
            updateMailboxLabel(world, mailboxPos, targetUsername, true);
        }

        int sentCount = sentStack.getCount() - stackToSend.getCount();
        if (sentCount > 0) {
            handStack.decrement(sentCount);
        }

        if (!stackToSend.isEmpty()) {
            sender.getInventory().offerOrDrop(stackToSend);
        }

        inventory.markDirty();
        cooldowns.put(sender.getUuid(), currentTime);

        if (sentCount > 0) {
            sender.sendMessage(Text.literal("Sent " + sentCount + "x " + sentStack.getName().getString() + " to " + targetUsername + "!").formatted(Formatting.GREEN), false);
        } else {
            sender.sendMessage(Text.literal("Could not send any items to " + targetUsername + "!").formatted(Formatting.RED), false);
        }

        ServerPlayerEntity recipient = world.getServer().getPlayerManager().getPlayer(targetUsername);
        if (recipient != null) {
            recipient.sendMessage(Text.literal("You received mail from " + sender.getName().getString() + "!").formatted(Formatting.AQUA), false);
            recipient.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
        } else {
            pendingMail.put(targetUsername, true);
        }

        return 1;
    }
}