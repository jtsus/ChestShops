package net.eterniamc.chestshops;

import com.flowpowered.math.vector.Vector3d;
import com.google.common.collect.Sets;
import net.minecraft.block.BlockChest;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.tileentity.carrier.Chest;
import org.spongepowered.api.block.trait.BooleanTraits;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.Item;
import org.spongepowered.api.entity.living.ArmorStand;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.ItemStackSnapshot;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.serializer.TextSerializers;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Set;
import java.util.UUID;

/**
 * Created by Justin
 */
public class ChestShop {
    private Set<ItemStack> contents = Sets.newConcurrentHashSet();
    private boolean open = false;
    private UUID owner;
    private Chest chest;
    private Item display;
    private ArmorStand title;
    private ArmorStand description;
    private Location<World> location;
    private double price;

    public ChestShop(Chest chest, UUID owner, double price) {
        this.chest = chest;
        this.owner = owner;
        this.location = chest.getLocation();
        this.price = price;
    }

    public static ChestShop readFromNbt(NBTTagCompound nbt) {
        ChestShop shop = new ChestShop(
                (Chest) Sponge.getServer().getWorld(nbt.getUniqueId("world")).get()
                        .getTileEntity(
                                nbt.getInteger("x"),
                                nbt.getInteger("y"),
                                nbt.getInteger("z")
                        )
                        .orElseThrow(()-> new Error("Could not find chest shop tile entity")),
                nbt.getUniqueId("owner"),
                nbt.getDouble("price")
        );
        NBTTagList stacks = nbt.getTagList("stacks", Constants.NBT.TAG_COMPOUND);
        for (NBTBase stack : stacks) {
            shop.contents.add((ItemStack)(Object)new net.minecraft.item.ItemStack((NBTTagCompound) stack));
        }
        return shop;
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setUniqueId("world", location.getExtent().getUniqueId());
        nbt.setInteger("x", location.getBlockX());
        nbt.setInteger("y", location.getBlockY());
        nbt.setInteger("z", location.getBlockZ());
        nbt.setUniqueId("owner", owner);
        nbt.setDouble("price", price);
        NBTTagList list = new NBTTagList();
        for (ItemStack content : contents) {
            list.appendTag(((net.minecraft.item.ItemStack) (Object) content).writeToNBT(new NBTTagCompound()));
        }
        nbt.setTag("stacks", list);
        return nbt;
    }

    public void open() {
        TileEntityChest entityChest = (TileEntityChest) chest;
        if (!open)
            entityChest.getWorld().addBlockEvent(new BlockPos(location.getX(), location.getY(), location.getZ()), entityChest.getBlockType(), 1, 1);
        open = true;
        if (title == null) {
            title = (ArmorStand)  location.getExtent().createEntity(EntityTypes.ARMOR_STAND, location.getPosition().add(.5, 1.5, .5));
            title.offer(Keys.INVISIBLE, true);
            title.offer(Keys.HAS_GRAVITY, false);
            title.offer(Keys.INFINITE_DESPAWN_DELAY, true);
            title.offer(Keys.DISPLAY_NAME, Text.of(
                    TextColors.BLUE,
                    !getContents().isEmpty() ? ((net.minecraft.item.ItemStack)(Object) contents.iterator().next()).getDisplayName() : "Empty"
            ));
            title.offer(Keys.CUSTOM_NAME_VISIBLE, true);
            title.offer(Keys.ARMOR_STAND_MARKER, true);
            location.getExtent().spawnEntity(title);
        }
        if (!getContents().isEmpty()) {
            if (display == null) {
                display = (Item) location.getExtent().createEntity(EntityTypes.ITEM, location.getPosition().add(.5, 1, .5));
                ItemStack snapshot = getContents().iterator().next().copy();
                snapshot.setQuantity(1);
                display.offer(Keys.REPRESENTED_ITEM, snapshot.createSnapshot());
                display.offer(Keys.INFINITE_DESPAWN_DELAY, true);
                display.offer(Keys.EXPIRATION_TICKS, Integer.MAX_VALUE);
                display.setVelocity(new Vector3d());
                location.getExtent().spawnEntity(display);
            }
            if (description == null) {
                description = (ArmorStand)  location.getExtent().createEntity(EntityTypes.ARMOR_STAND, location.getPosition().add(.5, 1.25, .5));
                description.offer(Keys.INVISIBLE, true);
                description.offer(Keys.HAS_GRAVITY, false);
                description.offer(Keys.INFINITE_DESPAWN_DELAY, true);
                description.offer(Keys.CUSTOM_NAME_VISIBLE, true);
                description.offer(Keys.ARMOR_STAND_MARKER, true);
                String price = (getPrice()+"").replaceAll("[.]0.*", "");
                description.offer(Keys.DISPLAY_NAME, TextSerializers.FORMATTING_CODE.deserialize("&7Price: &e"+price+" &8|&7 Available: &a"+getContents().stream().mapToInt(ItemStack::getQuantity).sum()));
                location.getExtent().spawnEntity(description);
            }
        }
    }

    public void close() {
        TileEntityChest entityChest = (TileEntityChest) chest;
        if (open)
            entityChest.getWorld().addBlockEvent(new BlockPos(location.getX(), location.getY(), location.getZ()), entityChest.getBlockType(), 1, 0);
        open = false;
        if (display != null) {
            display.remove();
            display = null;
        }
        if (title != null) {
            title.remove();
            title = null;
        }
        if (description != null) {
            description.remove();
            description = null;
        }
    }

    public void update() {
        if (display != null)
            display.remove();
        display = null;
        if (title != null)
            title.remove();
        title = null;
        if (description != null)
            description.remove();
        description = null;
    }

    public Set<ItemStack> getContents() {
        return contents;
    }

    public int sumContents() {
        return contents.stream().mapToInt(ItemStack::getQuantity).sum();
    }

    public void add(ItemStack stack) {
        for (ItemStack content : contents) {
            if (content.getQuantity() < content.getMaxStackQuantity()) {
                int toAdd = Math.min(stack.getQuantity(), content.getMaxStackQuantity() - content.getQuantity());
                content.setQuantity(content.getQuantity() + toAdd);
                stack.setQuantity(stack.getQuantity() - toAdd);
                if (stack.getQuantity() == 0)
                    return;
            }
        }
        contents.add(stack);
    }

    public Set<ItemStack> withdraw(int amount) {
        Set<ItemStack> set = Sets.newHashSet();
        for (ItemStack content : contents) {
            if (amount >= content.getQuantity()) {
                contents.remove(content);
                set.add(content);
                amount-=content.getQuantity();
            } else {
                ItemStack copy = content.copy();
                copy.setQuantity(amount);
                content.setQuantity(content.getQuantity() - copy.getQuantity());
                if (content.getQuantity() <= 0)
                    contents.remove(content);
                set.add(copy);
                break;
            }
        }
        if (sumContents() <= 0)
            contents.clear();
        update();
        return set;
    }

    public Location<World> getLocation() {
        return location;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public UUID getOwner() {
        return owner;
    }

    public Item getDisplay() {
        return display;
    }
}
