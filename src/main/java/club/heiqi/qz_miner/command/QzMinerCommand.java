package club.heiqi.qz_miner.command;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

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
        return "/qz_m <set_radiusLimit|set_blockLimit|set_chainRange> <value>";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("qz_m");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText("用法错误！使用 /qz_m <set_radiusLimit|set_blockLimit|set_pointFoundCache|set_chainRange> <值>"));
            return;
        }
        String subCommand = args[0];
        try {
            int value = Integer.parseInt(args[1]); // 将第二个参数解析为整数

            switch (subCommand) {
                case "set_radiusLimit" -> {
                    // 这里设置 radiusLimit 的值
                    Config.radiusLimit = value;
                    sender.addChatMessage(new ChatComponentText("radiusLimit 已设置为: " + value));
                    Config.save();
                    break;
                }
                case "set_blockLimit" -> {
                    // 这里设置 blockLimit 的值
                    Config.blockLimit = value;
                    sender.addChatMessage(new ChatComponentText("blockLimit 已设置为: " + value));
                    Config.save();
                    break;
                }
                case "set_pointFoundCache" -> {
                    Config.pointFounderCacheSize = value;
                    sender.addChatMessage(new ChatComponentText("pointFounderCacheSize 已设置为: " + value));
                    Config.save();
                    break;
                }

                default -> {
                    sender.addChatMessage(new ChatComponentText("未知的二级命令: " + subCommand));
                    break;
                }
            }
        } catch (NumberFormatException e) {
            sender.addChatMessage(new ChatComponentText("请输入一个有效的整数值！"));
        }
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender.canCommandSenderUseCommand(2, getCommandName());
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            // 返回子命令的自动补全
            return Arrays.asList("set_radiusLimit", "set_blockLimit", "set_chainRange", "set_pointFoundCache");
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
