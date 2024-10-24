package club.heiqi.qz_miner.Command;
import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class MinerCommand implements ICommand {
    public static void register(FMLServerStartingEvent event) {
        event.registerServerCommand(new MinerCommand());
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
                case "set_chainRange" -> {
                    Config.chainRange = value;
                    sender.addChatMessage(new ChatComponentText("chainRange 已设置为: " + value));
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

    /**
     * Returns true if the given command sender is allowed to use this command.
     *
     * @param sender
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender.canCommandSenderUseCommand(2, getCommandName());
    }

    /**
     * Adds the strings available in this command to the given list of tab completion options.
     *
     * @param sender
     * @param args
     */
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            // 返回子命令的自动补全
            return Arrays.asList("set_radiusLimit", "set_blockLimit", "set_chainRange", "set_pointFoundCache");
        }
        return null;
    }

    /**
     * Return whether the specified command parameter index is a username parameter.
     *
     * @param args
     * @param index
     */
    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    /**
     * Compares this object with the specified object for order.  Returns a
     * negative integer, zero, or a positive integer as this object is less
     * than, equal to, or greater than the specified object.
     *
     * <p>The implementor must ensure {@link Integer#signum
     * signum}{@code (x.compareTo(y)) == -signum(y.compareTo(x))} for
     * all {@code x} and {@code y}.  (This implies that {@code
     * x.compareTo(y)} must throw an exception if and only if {@code
     * y.compareTo(x)} throws an exception.)
     *
     * <p>The implementor must also ensure that the relation is transitive:
     * {@code (x.compareTo(y) > 0 && y.compareTo(z) > 0)} implies
     * {@code x.compareTo(z) > 0}.
     *
     * <p>Finally, the implementor must ensure that {@code
     * x.compareTo(y)==0} implies that {@code signum(x.compareTo(z))
     * == signum(y.compareTo(z))}, for all {@code z}.
     *
     * @param o the object to be compared.
     * @return a negative integer, zero, or a positive integer as this object
     * is less than, equal to, or greater than the specified object.
     * @throws NullPointerException if the specified object is null
     * @throws ClassCastException   if the specified object's type prevents it
     *                              from being compared to this object.
     * @apiNote It is strongly recommended, but <i>not</i> strictly required that
     * {@code (x.compareTo(y)==0) == (x.equals(y))}.  Generally speaking, any
     * class that implements the {@code Comparable} interface and violates
     * this condition should clearly indicate this fact.  The recommended
     * language is "Note: this class has a natural ordering that is
     * inconsistent with equals."
     */
    @Override
    public int compareTo(@NotNull Object o) {
        return 0;
    }
}
