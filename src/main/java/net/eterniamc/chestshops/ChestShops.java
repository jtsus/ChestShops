package net.eterniamc.chestshops;

import com.flowpowered.math.vector.Vector3i;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import net.minecraft.block.BlockChest;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.block.tileentity.TileEntity;
import org.spongepowered.api.block.tileentity.carrier.Chest;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.type.HandTypes;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.block.tileentity.TargetTileEntityEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.filter.cause.Root;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.event.item.inventory.ChangeInventoryEvent;
import org.spongepowered.api.event.message.MessageChannelEvent;
import org.spongepowered.api.event.service.ChangeServiceProviderEvent;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.economy.EconomyService;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.channel.MessageReceiver;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.format.TextStyles;
import org.spongepowered.api.text.serializer.TextSerializers;
import org.spongepowered.api.util.Direction;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.api.util.blockray.BlockRay;
import org.spongepowered.api.util.blockray.BlockRayHit;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Plugin(
        id = "chestshops",
        name = "ChestShops",
        description = "The ultimate chest shop",
        authors = {
                "Justin"
        }
)
public class ChestShops {
    private static final File file = new File("./config/chestShops.nbt");
    private static Map<Vector3i, ChestShop> shops = Maps.newHashMap();
    private Map<UUID, Consumer<Text>> chatGuis = Maps.newHashMap();
    private EconomyService es;

    @Inject
    private Logger logger;

    @Listener
    public void onServerStart(GameStartedServerEvent event) {
        es = Sponge.getServiceManager().provide(EconomyService.class).orElseThrow(()-> new Error("Economy service not found!"));
        if (file.exists()) {
            try {
                NBTTagCompound nbt = CompressedStreamTools.read(file);
                NBTTagList list = nbt.getTagList("shops", Constants.NBT.TAG_COMPOUND);
                for (NBTBase base : list) {
                    try {
                        ChestShop shop = ChestShop.readFromNbt((NBTTagCompound) base);
                        shops.put(shop.getLocation().getBlockPosition(), shop);
                    } catch(Exception e) {}
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Task.builder()
                .intervalTicks(2)
                .execute(() -> {
                    Map<Vector3i, ChestShop> copy = Maps.newHashMap();
                    copy.putAll(shops);
                    Collection<ChestShop> close = copy.values();
                    Collection<ChestShop> open = Sets.newHashSet();
                    for (Player player : Sponge.getServer().getOnlinePlayers()) {
                        BlockRay<World> ray = BlockRay.from(player)
                                .distanceLimit(6)
                                .build();
                        while (ray.hasNext()) {
                            BlockRayHit<World> hit = ray.next();
                            if (copy.containsKey(hit.getBlockPosition())) {
                                ChestShop shop = copy.get(hit.getBlockPosition());
                                if (shop.getLocation().getExtent().getName().equals(player.getWorld().getName())) {
                                    open.add(shop);
                                    close.remove(shop);
                                }
                                break;
                            }
                        }
                    }
                    Task.builder().execute(()-> {
                        open.forEach(ChestShop::open);
                        close.forEach(ChestShop::close);
                    }).submit(this);
                })
                .async()
                .submit(this);
        Task.builder()
                .interval(5, TimeUnit.MINUTES)
                .execute(this::save)
                .submit(this);
        Sponge.getCommandManager().register(
                this,
                CommandSpec.builder()
                        .permission("chestshops.give")
                        .arguments(
                                GenericArguments.playerOrSource(Text.of("player"))
                        )
                        .executor(((src, args) -> {
                            Player player = args.<Player>getOne("player").get();
                            ItemStack item = ItemStack.builder()
                                    .itemType(ItemTypes.CHEST)
                                    .add(Keys.DISPLAY_NAME, Text.of(TextColors.GOLD, TextStyles.BOLD, "Chest Shop"))
                                    .add(Keys.ITEM_LORE, Collections.singletonList(Text.of(TextColors.GRAY, "Place to create your chest shop")))
                                    .build();
                            ((net.minecraft.item.ItemStack) (Object) item).getTagCompound().setBoolean("ChestShop", true);
                            return player.getInventory().offer(item).getRejectedItems().isEmpty() ? CommandResult.success() : CommandResult.empty();
                        }))
                        .build(),
                "chestshop"
        );
    }

    @Listener
    public void onServiceProviderChange(ChangeServiceProviderEvent event) {
        if (event.getNewProvider() instanceof EconomyService)
            es = (EconomyService) event.getNewProvider();
    }

    @Listener
    public void onChestPlaced(ChangeBlockEvent.Place event, @First Player player) {
        for (Transaction<BlockSnapshot> transaction : event.getTransactions()) {
            if (transaction.getDefault().getState().getType() instanceof BlockChest) {
                net.minecraft.item.ItemStack na = (net.minecraft.item.ItemStack) (Object) player.getItemInHand(HandTypes.MAIN_HAND).get();
                if (na.hasTagCompound() && na.getTagCompound().hasKey("ChestShop")) {
                    Optional<TileEntity> tile = player.getWorld().getTileEntity(transaction.getDefault().getPosition());
                    if (tile.isPresent() && tile.get() instanceof Chest) {
                        Chest c = (Chest) tile.get();
                        if (c.getConnectedChests().size() == 1) {
                            BlockType type = transaction.getDefault().getState().getType() == BlockTypes.CHEST ? BlockTypes.TRAPPED_CHEST : BlockTypes.CHEST;
                            c.getLocation().setBlock(type.getDefaultState().with(Keys.DIRECTION, transaction.getDefault().getState().get(Keys.DIRECTION).orElse(Direction.NONE)).get());
                            tile = player.getWorld().getTileEntity(transaction.getDefault().getPosition());
                            c = (Chest) tile.get();
                        }
                        Chest chest = c; //lambdas >:(
                        sendMessage(player, "Please enter the price for items in this chest shop");
                        chatGuis.put(player.getUniqueId(), text -> {
                            ChestShop shop = new ChestShop(
                                    chest,
                                    player.getUniqueId(),
                                    Double.parseDouble(text.toPlain().replaceAll("[^0-9.]*", ""))
                            );
                            ChestShop old = shops.put(chest.getLocation().getBlockPosition(), shop);
                            if (old != null)
                                old.close();
                            sendMessage(player, "Your chest shop has been created! Right click it with an item stack to add it to your shop " +
                                    "and right click with an empty hand to remove a stack from your shop");
                        });
                        return;
                    }
                }
            }
        }
    }

    @Listener
    public void onChestBreak(ChangeBlockEvent.Break event) {
        for (Transaction<BlockSnapshot> transaction : event.getTransactions()) {
            ChestShop shop = shops.get(transaction.getDefault().getPosition());
            if (shop != null) {
                if (event.getSource() instanceof Player && !shop.getOwner().equals(((Player) event.getSource()).getUniqueId()))
                    event.setCancelled(true);
                else {
                    shops.remove(transaction.getDefault().getPosition());
                    System.out.println(shops);
                    shop.close();
                    Sponge.getServer().getPlayer(shop.getOwner()).ifPresent(player -> shop.withdraw(shop.sumContents()).forEach(player.getInventory()::offer));
                }
            }
        }
    }

    @Listener
    public void onPlayerInteractBlock(InteractBlockEvent.Secondary event, @First Player player) {
        if (shops.containsKey(event.getTargetBlock().getPosition())) {
            ChestShop shop = shops.get(event.getTargetBlock().getPosition());
            if (shop.getLocation().getExtent().getName().equals(player.getWorld().getName())) {
                event.setUseBlockResult(Tristate.FALSE);
                event.setUseItemResult(Tristate.FALSE);
                if (shop.getOwner().equals(player.getUniqueId())) {
                    Optional<ItemStack> held = player.getItemInHand(HandTypes.MAIN_HAND);
                    if (held.isPresent() && held.get().getType() != ItemTypes.AIR) {
                        ItemStack stack = shop.getContents().isEmpty() ? null : shop.getContents().iterator().next();
                        if (stack == null || stack.getType() == held.get().getType() && stack.getValues().equals(held.get().getValues())) {
                            shop.add(held.get());
                            shop.update();
                            player.setItemInHand(HandTypes.MAIN_HAND, ItemStack.empty());
                            sendMessage(player,"Items have been added to the shop");
                        } else {
                            sendMessage(player, "This chest shop can only hold " + stack.get(Keys.DISPLAY_NAME).orElse(Text.of(stack.getType().getName())) + ", remove before adding this item");
                        }
                    } else if (!shop.getContents().isEmpty()) {
                        ItemStack remove = shop.getContents().iterator().next();
                        shop.getContents().remove(remove);
                        shop.update();
                        player.setItemInHand(HandTypes.MAIN_HAND, remove);
                        sendMessage(player, "Items have been removed from the shop");
                    } else {
                        sendMessage(player, "This chest shop is empty!");
                    }
                } else if (shop.sumContents() > 1) {
                    sendMessage(player, "How many of these would you like to buy? (Enter the amount in chat)");
                    chatGuis.put(player.getUniqueId(), text -> {
                        int amount = Integer.parseInt(text.toPlain().replaceAll("[^0-9]",""));
                        if (shop.sumContents() < amount)
                            sendMessage(player, "This shop doesn't have that many items!");
                        else {
                            player.sendMessage(
                                    Text.builder()
                                            .append(TextSerializers.FORMATTING_CODE.deserialize("&6&lChest Shops &7&l>&f This costs "+amount*shop.getPrice()+", click &aHERE&f to purchase"))
                                            .onClick(TextActions.executeCallback(src -> {
                                                if (withdraw(player, amount * shop.getPrice())) {
                                                    deposit(getUser(shop.getOwner()), amount * shop.getPrice());
                                                    Set<ItemStack> withdrawn = shop.withdraw(amount);
                                                    withdrawn.forEach(player.getInventory()::offer);
                                                }
                                            }))
                                            .build()
                            );
                        }
                    });
                } else if (shop.sumContents() == 1) {
                    player.sendMessage(
                            Text.builder()
                                    .append(TextSerializers.FORMATTING_CODE.deserialize("&6&lChest Shops &7&l>&f This costs "+shop.getPrice()+", click &aHERE&f to purchase"))
                                    .onClick(TextActions.executeCallback(src -> {
                                        if (withdraw(player, shop.getPrice())) {
                                            deposit(getUser(shop.getOwner()), shop.getPrice());
                                            Set<ItemStack> withdrawn = shop.withdraw(1);
                                            withdrawn.forEach(player.getInventory()::offer);
                                        }
                                    }))
                                    .build()
                    );
                } else {
                    sendMessage(player, "This shop is empty!");
                }
            }
        }
    }

    @Listener
    public void onPlayerSendMessage(MessageChannelEvent event, @Root Player player) {
        if (chatGuis.containsKey(player.getUniqueId())) {
            chatGuis.remove(player.getUniqueId()).accept(event.getMessage());
            event.setMessageCancelled(true);
        }
    }

    @Listener
    public void onPlayerCollectsItem(ChangeInventoryEvent.Pickup.Pre event) {
        event.setCancelled(shops.values().stream().anyMatch(s -> s.getDisplay() == event.getTargetEntity()));
    }

    @Listener
    public void onServerStopping(GameStoppingServerEvent event) {
        Sponge.getScheduler().getScheduledTasks(this).forEach(Task::cancel);
        shops.values().forEach(ChestShop::close);
        save();
    }

    private void save() {
        NBTTagList list = new NBTTagList();
        shops.values().forEach(shop -> list.appendTag(shop.writeToNbt()));
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("shops", list);
        try {
            if (!file.exists())
                file.createNewFile();
            CompressedStreamTools.write(nbt, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(MessageReceiver receiver, String text) {
        receiver.sendMessage(TextSerializers.FORMATTING_CODE.deserialize("&6&lChest Shops &7&l>&f "+text));
    }

    private boolean withdraw(User user, double amount) {
        TransactionResult result = es.getOrCreateAccount(user.getUniqueId())
                .orElseThrow(()-> new Error("No account found for "+user.getName()))
                .withdraw(es.getDefaultCurrency(), new BigDecimal(amount), Cause.of(EventContext.empty(), this));
        return result.getResult() == ResultType.SUCCESS;
    }

    private boolean deposit(User user, double amount) {
        TransactionResult result = es.getOrCreateAccount(user.getUniqueId())
                .orElseThrow(()-> new Error("No account found for "+user.getName()))
                .deposit(es.getDefaultCurrency(), new BigDecimal(amount), Cause.of(EventContext.empty(), this));
        return result.getResult() == ResultType.SUCCESS;
    }

    private User getUser(UUID uuid) {
        return Sponge.getServiceManager().provideUnchecked(UserStorageService.class).get(uuid).orElseThrow(()-> new Error("No user found with uuid: "+uuid));
    }
}