package club.heiqi.qz_miner.command;

import club.heiqi.qz_miner.config.ServerConfigHotApplyService;
import club.heiqi.qz_miner.config.ServerConfigMutationService;
import club.heiqi.qz_miner.config.ServerConfigMutationService.Result;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/** 运维服务端配置的 {@code /qzminer config} 根命令。 */
public final class QzMinerCommand extends CommandBase {

    private final ServerConfigMutationService mutationService;
    private final ServerConfigHotApplyService hotApplyService;

    /** @param mutationService Authority 修改服务 @param hotApplyService 运行态发布服务 */
    public QzMinerCommand(
            ServerConfigMutationService mutationService,
            ServerConfigHotApplyService hotApplyService) {
        if (mutationService == null || hotApplyService == null) {
            throw new IllegalArgumentException("mutationService/hotApplyService must not be null");
        }
        this.mutationService = mutationService;
        this.hotApplyService = hotApplyService;
    }

    @Override
    public String getCommandName() {
        return "qzminer";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/qzminer config list [prefix]|get <path>|set <path> <value...>|reload";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Result result = route(args);
        if (result.isSuccess() && result.committed() != null) {
            try {
                hotApplyService.apply(result.committed());
            } catch (RuntimeException error) {
                sender.addChatMessage(new ChatComponentText(
                        "config committed but hot apply failed; retry reload: " + message(error)));
                return;
            }
        }
        for (String line : result.lines()) {
            sender.addChatMessage(new ChatComponentText(line));
        }
    }

    private Result route(String[] args) {
        if (args == null || args.length < 2 || !"config".equals(args[0])) {
            return usageFailure();
        }
        String operation = args[1];
        if ("list".equals(operation) && args.length <= 3) {
            return mutationService.list(args.length == 3 ? args[2] : "");
        }
        if ("get".equals(operation) && args.length == 3) {
            return mutationService.get(args[2]);
        }
        if ("set".equals(operation) && args.length >= 4) {
            return mutationService.set(args[2], join(args, 3));
        }
        if ("reload".equals(operation) && args.length == 2) {
            return mutationService.reload();
        }
        return usageFailure();
    }

    private Result usageFailure() {
        return ServerConfigMutationService.Result.commandFailure(
                "Usage: /qzminer config list [prefix]|get <path>|set <path> <value...>|reload");
    }

    private static String join(String[] values, int start) {
        StringBuilder joined = new StringBuilder();
        for (int i = start; i < values.length; i++) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(values[i]);
        }
        return joined.toString();
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
