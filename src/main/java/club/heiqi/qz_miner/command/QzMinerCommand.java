package club.heiqi.qz_miner.command;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class QzMinerCommand implements ICommand {
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
        Config.walkMap(f -> {
            String origin = usageList.get();
            String result;
            if (origin.isEmpty()) {
                result = f.name + "(" + f.description + ") " +
                    "min:" + (f.minValue == null ? "" : f.minValue) + ", " +
                    "max:" + (f.maxValue == null ? "" : f.maxValue) + ", " +
                    "default:" + f.defaultValue;
                sender.addChatMessage(new ChatComponentText(result));
            } else {
                String temp = " | " + f.name + "(" + f.description + ") " +
                    "min:" + (f.minValue == null ? "" : f.minValue) + ", " +
                    "max:" + (f.maxValue == null ? "" : f.maxValue) + ", " +
                    "default:" + f.defaultValue;
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
            Config.walkMap(f -> {
                try {
                    sender.addChatMessage(new ChatComponentText(f.name + ": " + f.field.get(null)));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            });
            return;
        }
        if (Objects.equals(subCommand, "help")) {
            getCommandUsage(sender);
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
        Config.walkMap(f -> {
            if (Objects.equals(f.name, subCommand)) {
                try {
                    f.field.set(null, finalValue);
                    sender.addChatMessage(new ChatComponentText(f.name + " 已设置为: " + f.field.get(null)));
                    Config.globalVarToSave();
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
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
            Config.walkMap(f -> result.add(f.name));
            result.add("check");
            result.add("help");
            return result;
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
