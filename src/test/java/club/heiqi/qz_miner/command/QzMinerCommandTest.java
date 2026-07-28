package club.heiqi.qz_miner.command;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ServerConfigHotApplyService;
import club.heiqi.qz_miner.config.ServerConfigMutationService;
import cpw.mods.fml.relauncher.FMLInjectionData;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.IChatComponent;

/** 命令权限、路由、空格值与失败不热发布语义。 */
public class QzMinerCommandTest {

    private File tempDir;
    private Field minecraftHomeField;
    private Object previousMinecraftHome;
    private List<String> messages;
    private AtomicInteger hotApplyCallbacks;
    private QzMinerCommand command;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-command-").toFile();
        minecraftHomeField = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHomeField.setAccessible(true);
        previousMinecraftHome = minecraftHomeField.get(null);
        minecraftHomeField.set(null, tempDir);
        ConfigBootstrap.resetForTests();
        ConfigBootstrap.bootstrap(tempDir, null);
        messages = new ArrayList<String>();
        hotApplyCallbacks = new AtomicInteger();
        command = new QzMinerCommand(
                ServerConfigMutationService.fromBootstrap(),
                new ServerConfigHotApplyService(new ServerConfigHotApplyService.Callbacks() {
                    @Override
                    public void publishPolicy(CommittedSnapshot committed) {
                        hotApplyCallbacks.incrementAndGet();
                    }

                    @Override
                    public void revalidateOnlineAccepted(CommittedSnapshot committed) {
                        hotApplyCallbacks.incrementAndGet();
                    }
                }));
    }

    @After
    public void tearDown() throws Exception {
        ConfigBootstrap.resetForTests();
        minecraftHomeField.set(null, previousMinecraftHome);
        deleteRecursively(tempDir);
    }

    @Test
    public void exposesRootMetadataAndPermissionFour() {
        Assert.assertEquals("qzminer", command.getCommandName());
        Assert.assertEquals(4, command.getRequiredPermissionLevel());
        Assert.assertTrue(command.getCommandUsage(sender()).contains("config list"));
    }

    @Test
    public void setJoinsRemainingArgumentsAndHotAppliesSuccessfulCommit() {
        command.processCommand(sender(), new String[] {
                "config", "set", "general.greeting", "Hello", "Dedicated", "Server"
        });

        Assert.assertEquals("Hello Dedicated Server", Config.greeting);
        Assert.assertEquals("Hello Dedicated Server",
                ConfigBootstrap.manager().authority().get("general.greeting"));
        Assert.assertEquals(2, hotApplyCallbacks.get());
        Assert.assertTrue(messages.toString(), messages.get(0).contains("Hello Dedicated Server"));
    }

    @Test
    public void invalidSetReportsFailureWithoutHotApply() {
        command.processCommand(sender(), new String[] {
                "config", "set", "general.enableUnlimitedOreFortune", "TRUE"
        });

        Assert.assertEquals(0, hotApplyCallbacks.get());
        Assert.assertFalse(Config.enableUnlimitedOreFortune);
        Assert.assertTrue(messages.toString(), messages.get(0).contains("exactly true or false"));
    }

    private ICommandSender sender() {
        return (ICommandSender) Proxy.newProxyInstance(
                ICommandSender.class.getClassLoader(),
                new Class<?>[] { ICommandSender.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("addChatMessage".equals(method.getName())) {
                            messages.add(((IChatComponent) args[0]).getUnformattedText());
                            return null;
                        }
                        if (method.getReturnType() == Boolean.TYPE) {
                            return Boolean.TRUE;
                        }
                        if (method.getReturnType() == Integer.TYPE) {
                            return Integer.valueOf(0);
                        }
                        if (method.getReturnType() == String.class) {
                            return "test";
                        }
                        return null;
                    }
                });
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
