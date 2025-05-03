package club.heiqi.qz_miner.command;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.metatileentity.CoverableTileEntity;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class QzMinerCommand extends CommandBase {
    public static Logger LOG = LogManager.getLogger();
    public void register(FMLServerStartingEvent event) {
        event.registerServerCommand(this);
    }
    @Override
    public String getCommandName() {
        return "qz_m";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        AtomicReference<String> usageList = new AtomicReference<>("");
        Config.walkMap(property -> {
            String origin = usageList.get();
            String result;
            if (origin.isEmpty()) {
                result = property.getName() + "(" + property.comment + ") " +
                    "min:" + (property.getMinValue() == null ? "" : property.getMinValue()) + ", " +
                    "max:" + (property.getMaxValue() == null ? "" : property.getMaxValue()) + ", " +
                    "default:" + property.getDefault();
                sender.addChatMessage(new ChatComponentText(result));
            } else {
                String temp = " | " + property.getName() + "(" + property.comment + ") " +
                    "min:" + (property.getMinValue() == null ? "" : property.getMinValue()) + ", " +
                    "max:" + (property.getMaxValue() == null ? "" : property.getMaxValue()) + ", " +
                    "default:" + property.getDefault();
                result = origin + temp;
                sender.addChatMessage(new ChatComponentText(temp));
            }
            usageList.set(result);
        });
        return usageList.get();
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("qz_m");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            getCommandUsage(sender);
            return;
        }
        String subCommand = args[0];
        if (Objects.equals(subCommand, "check")) {
            Config.walkMap(property -> {
                String propertyName = property.getName();
                Field field;
                String value = null;
                try {
                    field = Config.class.getField(propertyName);
                    value = field.get(null).toString();
                } catch (NoSuchFieldException e) {
                    sender.addChatMessage(new ChatComponentText("无法获取字段:" + propertyName));
                } catch (IllegalAccessException e) {
                    sender.addChatMessage(new ChatComponentText("无法获取字段值:" + propertyName));
                }
                sender.addChatMessage(new ChatComponentText(property.getName() + ": " + value));
            });
            return;
        }
        if (Objects.equals(subCommand, "help")) {
            getCommandUsage(sender);
            return;
        }
        if (Objects.equals(subCommand, "addWhite")) {
            String name = sender.getCommandSenderName();
            World world = sender.getEntityWorld();
            EntityPlayer player = world.getPlayerEntityByName(name);
            Vec3 vec3 = Vec3.createVectorHelper(player.posX,player.posY,player.posZ);
            float f1 = MathHelper.cos(-player.rotationYaw * 0.017453292F - (float)Math.PI);
            float f2 = MathHelper.sin(-player.rotationYaw * 0.017453292F - (float)Math.PI);
            float f3 = -MathHelper.cos(-player.rotationPitch * 0.017453292F);
            float f4 = MathHelper.sin(-player.rotationPitch * 0.017453292F);
            Vec3 vec31 = Vec3.createVectorHelper((double)(f2 * f3), (double)f4, (double)(f1 * f3));
            Vec3 vec32 = vec3.addVector(vec31.xCoord, vec31.yCoord, vec31.zCoord);
            MovingObjectPosition mop = player.worldObj.func_147447_a(vec3, vec32, false, false, true);
            Block block = world.getBlock(mop.blockX,mop.blockY,mop.blockZ);
            int meta = world.getBlockMetadata(mop.blockX,mop.blockY,mop.blockZ);
            int mID = 0;
            TileEntity tile = world.getTileEntity(mop.blockX,mop.blockY,mop.blockZ);
            if (tile instanceof CoverableTileEntity cT) {
                Field f = null;
                try {
                    f = cT.getClass().getField("mID");
                    f.setAccessible(true);
                    mID = f.getInt(cT);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    LOG.error("反射获取mID出现异常");
                    sender.addChatMessage(new ChatComponentText("反射获取mID出现异常"));
                    return;
                }
            }
            // 设置配置
            List<String> c = new ArrayList<>();
            for (String s : Config.whiteList) c.add(s);
            String toAdd = "stringID:" + GameRegistry.findUniqueIdentifierFor(block) + ",blockMeta:" + meta + ",mID:" + mID;
            c.add(toAdd);
            String[] n = new String[c.size()];
            c.forEach(component -> n[c.indexOf(component)]=component);
            Property whiteList = Config.config.get(Configuration.CATEGORY_GENERAL,"whiteList",Config.whiteList,"连锁组白名单列表");
            Config.whiteList = n;
            whiteList.set(n);
            Config.save();
            return;
        }
        // 匹配整数的正则表达式：正负整数
        String integerRegex = "^-?\\d+$";
        // 匹配小数的正则表达式：正负小数（含小数点）
        String decimalRegex = "^-?\\d+\\.\\d+$";
        Object value;
        if (args[1].matches(integerRegex)) {
            value = Integer.parseInt(args[1]);
        } else if (args[1].matches(decimalRegex)) {
            value = Double.parseDouble(args[1]);
        } else {
            sender.addChatMessage(new ChatComponentText("请输入一个有效数值！"));
            return;
        }
        Object finalValue = value;
        Config.walkMap(property -> {
            if (Objects.equals(property.getName(), subCommand)) {
                try {
                    Field field = Config.class.getField(property.getName());
                    if (property.isIntValue()) {
                        int intValue = Integer.parseInt(finalValue.toString());
                        property.set(intValue);
                        field.setInt(null,intValue);
                    } else if (property.isDoubleValue()) {
                        double doubleValue = Double.parseDouble(finalValue.toString());
                        float floatValue = Float.parseFloat(finalValue.toString());
                        property.set(doubleValue);
                        if (field.getType() == double.class) {
                            field.setDouble(null,doubleValue);
                        }
                        else if (field.getType() == float.class) {
                            field.setFloat(null,floatValue);
                        }
                    } else if (property.isBooleanValue()) {
                        boolean parsedValue;
                        if (finalValue instanceof Number) {
                            parsedValue = ((Number) finalValue).intValue() > 0;
                        } else {
                            parsedValue = Boolean.parseBoolean(finalValue.toString());
                        }
                        property.set(parsedValue);
                        field.setBoolean(null,parsedValue);
                    } else {
                        sender.addChatMessage(new ChatComponentText("不支持的字段类型"));
                        return;
                    }

                    sender.addChatMessage(new ChatComponentText(property.getName() + " 已设置为: " + property.getString()));
                    Config.save();
                } catch (NumberFormatException e) {
                    sender.addChatMessage(new ChatComponentText("无法设置字段值: " + e.getMessage()));
                } catch (NoSuchFieldException e) {
                    sender.addChatMessage(new ChatComponentText("配置中不存在此字段: " + e.getMessage()));
                } catch (IllegalAccessException e) {
                    sender.addChatMessage(new ChatComponentText("无法设置字段: " + e.getMessage()));
                }
            }
        });
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender.canCommandSenderUseCommand(2, getCommandName());
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            Config.walkMap(f -> result.add(f.getName()));
            result.add("check");
            result.add("help");
            result.add("addWhite");
            return getListOfStringsFromIterableMatchingLastWord(args,result);
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    @Override
    public int compareTo(@NotNull Object o) {
        return 0;
    }
}
